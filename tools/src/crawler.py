"""
Crawler Engine - Main orchestration for document crawling.

Coordinates:
- Source discovery (sitemap/URL discovery)
- Content fetching (HTTP client)
- Content extraction (HTML/PDF/DOCX)
- Change detection (hash-based)
- Document processing (revision processor)
"""

import logging
from typing import List, Optional, Set
from dataclasses import dataclass
from datetime import datetime

from .config import CrawlerConfig
from .database import Database
from .http_client import HTTPClient
from .source_registry import SourceRegistry, CrawlSource
from .sitemap_parser import SitemapParser, SitemapURL
from .url_discovery import URLDiscovery
from .robots import RobotsChecker
from .extractors.html import HTMLExtractor
from .extractors.pdf import PDFExtractor
from .extractors.docx import DOCXExtractor
from .hash_utils import compute_content_hash
from .url_utils import normalize_url
from .revision_processor import RevisionProcessor
from .job_manager import JobManager
from .exceptions import (
    HTTPError,
    RobotsDisallowedError,
    UnsupportedContentTypeError,
    ContentTooLargeError,
    ParsingError,
    SecurityError,
)


logger = logging.getLogger(__name__)


@dataclass
class CrawlResult:
    """Result of crawling a single URL."""
    
    url: str
    success: bool
    status: str  # 'new', 'changed', 'unchanged', 'error'
    error_message: Optional[str] = None
    document_id: Optional[int] = None
    revision_id: Optional[int] = None
    content_type: Optional[str] = None
    content_size: Optional[int] = None


class CrawlEngine:
    """Main crawler engine orchestrating discovery and processing."""
    
    def __init__(
        self,
        config: CrawlerConfig,
        database: Database,
        job_manager: JobManager,
        revision_processor: RevisionProcessor,
    ):
        """
        Initialize crawler engine.
        
        Args:
            config: Crawler configuration
            database: Database connection
            job_manager: Job manager for lease management
            revision_processor: Revision processor for document processing
        """
        self.config = config
        self.database = database
        self.job_manager = job_manager
        self.revision_processor = revision_processor
        
        # Initialize components
        self.http_client = HTTPClient(config)
        self.source_registry = SourceRegistry(database)
        self.robots_checker = RobotsChecker(self.http_client)
        
        # Initialize extractors
        self.html_extractor = HTMLExtractor()
        self.pdf_extractor = PDFExtractor()
        self.docx_extractor = DOCXExtractor()
        
        # Track processed URLs to avoid duplicates within single crawl
        self._processed_urls: Set[str] = set()
    
    def crawl_source(
        self,
        source: CrawlSource,
        job_id: int,
        job_claim_token: str,
    ) -> List[CrawlResult]:
        """
        Crawl a single source and process discovered documents.
        
        Args:
            source: Source configuration to crawl
            job_id: Job ID for lease management
            job_claim_token: Job claim token for ownership verification
            
        Returns:
            List of CrawlResult for each URL processed
        """
        logger.info(f"Starting crawl for source {source.id}: {source.name}")
        
        results = []
        self._processed_urls.clear()
        
        try:
            # Discover URLs from sitemap if available
            discovered_urls = []
            
            if source.sitemap_url:
                logger.info(f"Discovering URLs from sitemap: {source.sitemap_url}")
                sitemap_urls = self._discover_from_sitemap(source)
                discovered_urls.extend([u.loc for u in sitemap_urls])
                logger.info(f"Discovered {len(sitemap_urls)} URLs from sitemap")
            
            # If no sitemap or want additional discovery, could add seed URL crawling here
            # For now, focus on sitemap-based discovery per requirements
            
            if not discovered_urls:
                logger.warning(f"No URLs discovered for source {source.id}")
                return results
            
            # Process each discovered URL
            for url in discovered_urls:
                # Check job lease before each URL to ensure we still own the job
                try:
                    self.job_manager.verify_ownership(job_id, job_claim_token)
                except Exception as e:
                    logger.error(f"Lost job ownership: {e}")
                    break
                
                # Skip if already processed in this crawl
                if url in self._processed_urls:
                    continue
                
                # Process URL
                result = self._process_url(url, source, job_id, job_claim_token)
                results.append(result)
                self._processed_urls.add(url)
                
                # Send heartbeat periodically
                if len(results) % 10 == 0:
                    try:
                        self.job_manager.heartbeat(job_id, job_claim_token)
                        logger.debug(f"Heartbeat sent for job {job_id}")
                    except Exception as e:
                        logger.error(f"Heartbeat failed: {e}")
                        break
            
            logger.info(
                f"Completed crawl for source {source.id}: "
                f"{len(results)} URLs processed"
            )
            
            return results
            
        except Exception as e:
            logger.error(f"Crawl failed for source {source.id}: {e}", exc_info=True)
            raise
    
    def _discover_from_sitemap(self, source: CrawlSource) -> List[SitemapURL]:
        """
        Discover URLs from sitemap.
        
        Args:
            source: Source configuration
            
        Returns:
            List of SitemapURL objects
        """
        if not source.sitemap_url:
            return []
        
        try:
            # Create sitemap parser with source filters
            parser = SitemapParser(
                http_client=self.http_client,
                allowed_domains=[self._extract_domain(source.base_url)],
                allowed_path_patterns=source.allowed_path_patterns,
                excluded_path_patterns=source.excluded_path_patterns,
                max_urls=10000,  # Safety limit
            )
            
            # Parse sitemap
            urls = parser.parse_sitemap(source.sitemap_url)
            
            return urls
            
        except Exception as e:
            logger.error(f"Failed to parse sitemap {source.sitemap_url}: {e}")
            return []
    
    def _extract_domain(self, url: str) -> str:
        """Extract domain from URL."""
        from urllib.parse import urlparse
        parsed = urlparse(url)
        return parsed.netloc
    
    def _process_url(
        self,
        url: str,
        source: CrawlSource,
        job_id: int,
        job_claim_token: str,
    ) -> CrawlResult:
        """
        Process a single URL: fetch, extract, and create revision.
        
        Args:
            url: URL to process
            source: Source configuration
            job_id: Job ID for tracking
            job_claim_token: Job claim token
            
        Returns:
            CrawlResult with outcome
        """
        logger.info(f"Processing URL: {url}")
        
        try:
            # Normalize URL
            canonical_url = normalize_url(url)
            
            # Check robots.txt
            if not self._check_robots_allowed(canonical_url, source):
                logger.info(f"URL disallowed by robots.txt: {canonical_url}")
                return CrawlResult(
                    url=canonical_url,
                    success=False,
                    status='error',
                    error_message='Disallowed by robots.txt',
                )
            
            # Fetch content
            content, content_type, last_modified = self._fetch_content(
                canonical_url, source
            )
            
            if not content:
                return CrawlResult(
                    url=canonical_url,
                    success=False,
                    status='error',
                    error_message='Empty content',
                )
            
            # Extract text
            extracted_text = self._extract_text(content, content_type)
            
            if not extracted_text:
                return CrawlResult(
                    url=canonical_url,
                    success=False,
                    status='error',
                    error_message='No text extracted',
                )
            
            # Extract metadata
            metadata = self._extract_metadata(content, content_type, canonical_url)
            
            # Process document through revision processor
            revision_result = self.revision_processor.process_document(
                url=canonical_url,
                content=extracted_text,
                content_type=content_type,
                metadata=metadata,
                crawl_source_id=source.id,
                job_id=job_id,
                job_claim_token=job_claim_token,
            )
            
            # Determine status
            if revision_result['status'] == 'unchanged':
                status = 'unchanged'
            elif revision_result['is_new']:
                status = 'new'
            else:
                status = 'changed'
            
            return CrawlResult(
                url=canonical_url,
                success=True,
                status=status,
                document_id=revision_result.get('document_id'),
                revision_id=revision_result.get('revision_id'),
                content_type=content_type,
                content_size=len(content),
            )
            
        except RobotsDisallowedError as e:
            logger.info(f"Robots disallowed: {url}")
            return CrawlResult(
                url=url,
                success=False,
                status='error',
                error_message=str(e),
            )
        
        except (HTTPError, SecurityError, ContentTooLargeError) as e:
            logger.warning(f"Failed to fetch {url}: {e}")
            return CrawlResult(
                url=url,
                success=False,
                status='error',
                error_message=str(e),
            )
        
        except (ParsingError, UnsupportedContentTypeError) as e:
            logger.warning(f"Failed to extract content from {url}: {e}")
            return CrawlResult(
                url=url,
                success=False,
                status='error',
                error_message=str(e),
            )
        
        except Exception as e:
            logger.error(f"Unexpected error processing {url}: {e}", exc_info=True)
            return CrawlResult(
                url=url,
                success=False,
                status='error',
                error_message=f"Unexpected error: {e}",
            )
    
    def _check_robots_allowed(self, url: str, source: CrawlSource) -> bool:
        """
        Check if URL is allowed by robots.txt.
        
        Args:
            url: URL to check
            source: Source configuration
            
        Returns:
            True if allowed
        """
        try:
            return self.robots_checker.is_allowed(
                url,
                user_agent=self.config.user_agent,
            )
        except Exception as e:
            # If robots.txt fetch fails, allow crawling but log warning
            logger.warning(f"Failed to check robots.txt for {url}: {e}")
            return True
    
    def _fetch_content(
        self, url: str, source: CrawlSource
    ) -> tuple[bytes, str, Optional[str]]:
        """
        Fetch content from URL.
        
        Args:
            url: URL to fetch
            source: Source configuration for rate limiting
            
        Returns:
            Tuple of (content, content_type, last_modified)
        """
        # Apply source-specific rate limit
        import time
        time.sleep(source.rate_limit_seconds)
        
        # Fetch using HTTP client
        content, content_type, last_modified = self.http_client.fetch(
            url,
            respect_rate_limit=False,  # Already handled above
            validate_security=True,
        )
        
        return content, content_type, last_modified
    
    def _extract_text(self, content: bytes, content_type: str) -> str:
        """
        Extract text from content based on content type.
        
        Args:
            content: Raw content bytes
            content_type: MIME content type
            
        Returns:
            Extracted text
        """
        try:
            if content_type in ('text/html', 'application/xhtml+xml'):
                result = self.html_extractor.extract(content)
                return result['text']
            
            elif content_type == 'application/pdf':
                result = self.pdf_extractor.extract(content)
                return result['text']
            
            elif content_type in (
                'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
                'application/msword',
            ):
                result = self.docx_extractor.extract(content)
                return result['text']
            
            else:
                raise UnsupportedContentTypeError(f"Unsupported content type: {content_type}")
        
        except Exception as e:
            raise ParsingError(f"Failed to extract text: {e}") from e
    
    def _extract_metadata(
        self, content: bytes, content_type: str, url: str
    ) -> dict:
        """
        Extract metadata from content.
        
        Args:
            content: Raw content bytes
            content_type: MIME content type
            url: Source URL
            
        Returns:
            Metadata dictionary
        """
        metadata = {
            'source_url': url,
            'content_type': content_type,
        }
        
        try:
            if content_type in ('text/html', 'application/xhtml+xml'):
                result = self.html_extractor.extract(content)
                metadata.update({
                    'title': result.get('title'),
                    'description': result.get('description'),
                    'author': result.get('author'),
                })
            
            elif content_type == 'application/pdf':
                result = self.pdf_extractor.extract(content)
                metadata.update({
                    'title': result.get('title'),
                    'author': result.get('author'),
                })
            
            elif content_type in (
                'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
                'application/msword',
            ):
                result = self.docx_extractor.extract(content)
                metadata.update({
                    'title': result.get('title'),
                    'author': result.get('author'),
                })
        
        except Exception as e:
            logger.warning(f"Failed to extract metadata from {url}: {e}")
        
        # Try to extract campus and admission year from URL/metadata
        # This is heuristic-based, may need refinement
        metadata.update(self._infer_document_attributes(url, metadata))
        
        return metadata
    
    def _infer_document_attributes(self, url: str, metadata: dict) -> dict:
        """
        Infer campus and admission year from URL and metadata.
        
        Args:
            url: Document URL
            metadata: Extracted metadata
            
        Returns:
            Dictionary with inferred attributes
        """
        attributes = {}
        
        # Try to infer campus from URL patterns
        url_lower = url.lower()
        if 'ha-noi' in url_lower or 'hanoi' in url_lower:
            attributes['campus_id'] = 1  # Assuming 1 = Ha Noi
        elif 'ho-chi-minh' in url_lower or 'hcm' in url_lower:
            attributes['campus_id'] = 2  # Assuming 2 = Ho Chi Minh
        
        # Try to infer admission year from URL or title
        import re
        
        # Look for 4-digit years (2020-2030)
        year_pattern = r'20[2-3][0-9]'
        
        # Check URL
        year_match = re.search(year_pattern, url)
        if year_match:
            attributes['admission_year'] = int(year_match.group())
        
        # Check title if no year in URL
        elif 'title' in metadata and metadata['title']:
            year_match = re.search(year_pattern, metadata['title'])
            if year_match:
                attributes['admission_year'] = int(year_match.group())
        
        return attributes
    
    def close(self):
        """Close HTTP client and cleanup resources."""
        self.http_client.close()
