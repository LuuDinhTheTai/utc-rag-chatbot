"""
Sitemap Parser - Discovers URLs from XML sitemaps.

Supports:
- Standard sitemap.xml format
- Sitemap index files
- URL canonicalization
- Allowlist/blocklist filtering
- Error handling and retry
"""

import logging
import xml.etree.ElementTree as ET
from typing import List, Set, Optional, Tuple
from dataclasses import dataclass
from datetime import datetime
from urllib.parse import urljoin, urlparse

from .http_client import HTTPClient
from .url_utils import normalize_url, is_valid_url
from .exceptions import HTTPError, ValidationError


logger = logging.getLogger(__name__)


# XML namespaces for sitemap parsing
SITEMAP_NAMESPACES = {
    'sm': 'http://www.sitemaps.org/schemas/sitemap/0.9',
    'xhtml': 'http://www.w3.org/1999/xhtml',
    'image': 'http://www.google.com/schemas/sitemap-image/1.1',
    'video': 'http://www.google.com/schemas/sitemap-video/1.1',
}


@dataclass
class SitemapURL:
    """Represents a URL discovered from sitemap."""
    
    loc: str  # URL location (canonicalized)
    lastmod: Optional[datetime]  # Last modification time
    changefreq: Optional[str]  # Change frequency hint
    priority: Optional[float]  # Priority hint (0.0-1.0)
    source_sitemap: str  # Which sitemap this came from
    
    def __post_init__(self):
        """Validate URL after initialization."""
        if not self.loc:
            raise ValidationError("URL location cannot be empty")
        
        if not is_valid_url(self.loc):
            raise ValidationError(f"Invalid URL: {self.loc}")
        
        if self.priority is not None:
            if not 0.0 <= self.priority <= 1.0:
                raise ValidationError(f"Priority must be between 0.0 and 1.0, got {self.priority}")


class SitemapParser:
    """Parses XML sitemaps and discovers URLs."""
    
    def __init__(
        self,
        http_client: HTTPClient,
        allowed_domains: Optional[List[str]] = None,
        allowed_path_patterns: Optional[List[str]] = None,
        excluded_path_patterns: Optional[List[str]] = None,
        max_urls: int = 10000,
    ):
        """
        Initialize sitemap parser.
        
        Args:
            http_client: HTTP client for fetching sitemaps
            allowed_domains: List of allowed domains (e.g., ['utc.edu.vn'])
            allowed_path_patterns: List of allowed path regex patterns
            excluded_path_patterns: List of excluded path regex patterns
            max_urls: Maximum URLs to discover (safety limit)
        """
        self.http_client = http_client
        self.allowed_domains = set(allowed_domains or [])
        self.allowed_path_patterns = allowed_path_patterns or []
        self.excluded_path_patterns = excluded_path_patterns or []
        self.max_urls = max_urls
        
        # Track discovered URLs to avoid duplicates
        self._discovered: Set[str] = set()
        self._processed_sitemaps: Set[str] = set()
    
    def parse_sitemap(self, sitemap_url: str) -> List[SitemapURL]:
        """
        Parse a sitemap and return discovered URLs.
        
        Handles both regular sitemaps and sitemap index files.
        
        Args:
            sitemap_url: URL of sitemap.xml or sitemap index
            
        Returns:
            List of SitemapURL objects
            
        Raises:
            HTTPError: If fetching sitemap fails
            ValidationError: If sitemap XML is invalid
        """
        # Avoid processing same sitemap twice
        if sitemap_url in self._processed_sitemaps:
            logger.debug(f"Sitemap already processed: {sitemap_url}")
            return []
        
        self._processed_sitemaps.add(sitemap_url)
        
        logger.info(f"Parsing sitemap: {sitemap_url}")
        
        try:
            # Fetch sitemap content
            response = self.http_client.get(sitemap_url)
            
            # Parse XML
            root = ET.fromstring(response.content)
            
            # Determine if this is a sitemap index or regular sitemap
            if self._is_sitemap_index(root):
                return self._parse_sitemap_index(root, sitemap_url)
            else:
                return self._parse_sitemap_urls(root, sitemap_url)
                
        except ET.ParseError as e:
            raise ValidationError(f"Invalid XML in sitemap {sitemap_url}: {e}") from e
        except HTTPError as e:
            logger.error(f"Failed to fetch sitemap {sitemap_url}: {e}")
            raise
        except Exception as e:
            raise ValidationError(f"Failed to parse sitemap {sitemap_url}: {e}") from e
    
    def _is_sitemap_index(self, root: ET.Element) -> bool:
        """
        Check if XML root is a sitemap index.
        
        Args:
            root: XML root element
            
        Returns:
            True if this is a sitemap index
        """
        # Check for <sitemapindex> tag
        if root.tag.endswith('sitemapindex'):
            return True
        
        # Check for <sitemap> children (index format)
        sitemaps = root.findall('.//sm:sitemap', SITEMAP_NAMESPACES)
        if sitemaps:
            return True
        
        # Also check without namespace (some sitemaps don't use it)
        sitemaps_no_ns = root.findall('.//sitemap')
        return len(sitemaps_no_ns) > 0
    
    def _parse_sitemap_index(self, root: ET.Element, index_url: str) -> List[SitemapURL]:
        """
        Parse sitemap index and recursively fetch child sitemaps.
        
        Args:
            root: XML root element
            index_url: URL of the sitemap index
            
        Returns:
            List of all URLs from child sitemaps
        """
        logger.info(f"Parsing sitemap index: {index_url}")
        
        all_urls = []
        
        # Find all <sitemap> entries
        sitemaps = root.findall('.//sm:sitemap', SITEMAP_NAMESPACES)
        if not sitemaps:
            # Try without namespace
            sitemaps = root.findall('.//sitemap')
        
        logger.info(f"Found {len(sitemaps)} child sitemaps in index")
        
        for sitemap_elem in sitemaps:
            # Extract <loc> element
            loc_elem = sitemap_elem.find('sm:loc', SITEMAP_NAMESPACES)
            if loc_elem is None:
                loc_elem = sitemap_elem.find('loc')
            
            if loc_elem is None or not loc_elem.text:
                logger.warning(f"Sitemap entry missing <loc> in index {index_url}")
                continue
            
            child_sitemap_url = loc_elem.text.strip()
            
            # Safety check: avoid infinite recursion
            if len(self._processed_sitemaps) > 100:
                logger.warning(f"Too many sitemaps processed (>100), stopping recursion")
                break
            
            # Recursively parse child sitemap
            try:
                child_urls = self.parse_sitemap(child_sitemap_url)
                all_urls.extend(child_urls)
                
                # Check max_urls limit
                if len(all_urls) >= self.max_urls:
                    logger.warning(f"Reached max_urls limit ({self.max_urls}), stopping")
                    break
                    
            except Exception as e:
                logger.error(f"Failed to parse child sitemap {child_sitemap_url}: {e}")
                # Continue processing other child sitemaps
                continue
        
        return all_urls
    
    def _parse_sitemap_urls(self, root: ET.Element, sitemap_url: str) -> List[SitemapURL]:
        """
        Parse regular sitemap and extract URLs.
        
        Args:
            root: XML root element
            sitemap_url: URL of the sitemap
            
        Returns:
            List of SitemapURL objects
        """
        urls = []
        
        # Find all <url> entries
        url_entries = root.findall('.//sm:url', SITEMAP_NAMESPACES)
        if not url_entries:
            # Try without namespace
            url_entries = root.findall('.//url')
        
        logger.info(f"Found {len(url_entries)} URL entries in sitemap {sitemap_url}")
        
        for url_elem in url_entries:
            try:
                sitemap_url_obj = self._parse_url_entry(url_elem, sitemap_url)
                
                if sitemap_url_obj is None:
                    continue
                
                # Check if we should include this URL
                if not self._should_include_url(sitemap_url_obj.loc):
                    logger.debug(f"Excluding URL (filters): {sitemap_url_obj.loc}")
                    continue
                
                # Avoid duplicates
                if sitemap_url_obj.loc in self._discovered:
                    logger.debug(f"Duplicate URL: {sitemap_url_obj.loc}")
                    continue
                
                self._discovered.add(sitemap_url_obj.loc)
                urls.append(sitemap_url_obj)
                
                # Check max_urls limit
                if len(urls) >= self.max_urls:
                    logger.warning(f"Reached max_urls limit ({self.max_urls}), stopping")
                    break
                    
            except ValidationError as e:
                logger.warning(f"Invalid URL entry in sitemap: {e}")
                continue
        
        return urls
    
    def _parse_url_entry(self, url_elem: ET.Element, sitemap_url: str) -> Optional[SitemapURL]:
        """
        Parse a single <url> entry from sitemap.
        
        Args:
            url_elem: XML <url> element
            sitemap_url: Source sitemap URL
            
        Returns:
            SitemapURL object or None if invalid
        """
        # Extract <loc>
        loc_elem = url_elem.find('sm:loc', SITEMAP_NAMESPACES)
        if loc_elem is None:
            loc_elem = url_elem.find('loc')
        
        if loc_elem is None or not loc_elem.text:
            logger.warning("URL entry missing <loc> element")
            return None
        
        loc = loc_elem.text.strip()
        
        # Canonicalize URL
        try:
            loc = normalize_url(loc)
        except Exception as e:
            logger.warning(f"Failed to normalize URL {loc}: {e}")
            return None
        
        # Extract <lastmod>
        lastmod = None
        lastmod_elem = url_elem.find('sm:lastmod', SITEMAP_NAMESPACES)
        if lastmod_elem is None:
            lastmod_elem = url_elem.find('lastmod')
        
        if lastmod_elem is not None and lastmod_elem.text:
            lastmod = self._parse_datetime(lastmod_elem.text.strip())
        
        # Extract <changefreq>
        changefreq = None
        changefreq_elem = url_elem.find('sm:changefreq', SITEMAP_NAMESPACES)
        if changefreq_elem is None:
            changefreq_elem = url_elem.find('changefreq')
        
        if changefreq_elem is not None and changefreq_elem.text:
            changefreq = changefreq_elem.text.strip()
        
        # Extract <priority>
        priority = None
        priority_elem = url_elem.find('sm:priority', SITEMAP_NAMESPACES)
        if priority_elem is None:
            priority_elem = url_elem.find('priority')
        
        if priority_elem is not None and priority_elem.text:
            try:
                priority = float(priority_elem.text.strip())
            except ValueError:
                logger.warning(f"Invalid priority value: {priority_elem.text}")
        
        return SitemapURL(
            loc=loc,
            lastmod=lastmod,
            changefreq=changefreq,
            priority=priority,
            source_sitemap=sitemap_url,
        )
    
    def _parse_datetime(self, dt_str: str) -> Optional[datetime]:
        """
        Parse datetime from sitemap lastmod format.
        
        Supports various ISO 8601 formats:
        - 2024-01-15
        - 2024-01-15T10:30:00Z
        - 2024-01-15T10:30:00+07:00
        
        Args:
            dt_str: Datetime string
            
        Returns:
            datetime object or None if parsing fails
        """
        # Try various formats
        formats = [
            '%Y-%m-%d',
            '%Y-%m-%dT%H:%M:%SZ',
            '%Y-%m-%dT%H:%M:%S%z',
            '%Y-%m-%dT%H:%M:%S.%fZ',
            '%Y-%m-%dT%H:%M:%S.%f%z',
        ]
        
        for fmt in formats:
            try:
                return datetime.strptime(dt_str, fmt)
            except ValueError:
                continue
        
        logger.warning(f"Failed to parse datetime: {dt_str}")
        return None
    
    def _should_include_url(self, url: str) -> bool:
        """
        Check if URL should be included based on filters.
        
        Args:
            url: Canonicalized URL
            
        Returns:
            True if URL should be included
        """
        parsed = urlparse(url)
        domain = parsed.netloc.lower()
        path = parsed.path
        
        # Check allowed domains
        if self.allowed_domains:
            # Domain must match exactly or be a subdomain
            domain_allowed = False
            for allowed_domain in self.allowed_domains:
                if domain == allowed_domain or domain.endswith('.' + allowed_domain):
                    domain_allowed = True
                    break
            
            if not domain_allowed:
                logger.debug(f"Domain not in allowlist: {domain}")
                return False
        
        # Check excluded path patterns (process first, takes precedence)
        if self.excluded_path_patterns:
            import re
            for pattern in self.excluded_path_patterns:
                if re.search(pattern, path):
                    logger.debug(f"Path matches exclusion pattern '{pattern}': {path}")
                    return False
        
        # Check allowed path patterns
        if self.allowed_path_patterns:
            import re
            path_allowed = False
            for pattern in self.allowed_path_patterns:
                if re.search(pattern, path):
                    path_allowed = True
                    break
            
            if not path_allowed:
                logger.debug(f"Path does not match any allowed pattern: {path}")
                return False
        
        return True
    
    def reset(self):
        """Reset discovered URLs and processed sitemaps."""
        self._discovered.clear()
        self._processed_sitemaps.clear()
        logger.debug("Sitemap parser state reset")
