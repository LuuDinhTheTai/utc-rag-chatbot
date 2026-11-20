"""
URL Discovery - Extract and normalize URLs from HTML content.

Discovers internal links, applies canonicalization, checks allowlist/blocklist,
prevents loops, and limits discovery depth.
"""

import logging
import re
from typing import List, Set, Optional
from urllib.parse import urljoin, urlparse
from bs4 import BeautifulSoup

from .url_utils import normalize_url, is_valid_url, get_domain, is_same_domain
from .exceptions import ValidationError


logger = logging.getLogger(__name__)


class URLDiscovery:
    """Discovers and filters URLs from HTML content."""
    
    def __init__(
        self,
        base_url: str,
        allowed_domains: Optional[List[str]] = None,
        allowed_path_patterns: Optional[List[str]] = None,
        excluded_path_patterns: Optional[List[str]] = None,
        max_depth: int = 3,
        same_domain_only: bool = True,
    ):
        """
        Initialize URL discovery.
        
        Args:
            base_url: Base URL for resolving relative links
            allowed_domains: List of allowed domains (e.g., ['utc.edu.vn'])
            allowed_path_patterns: List of allowed path regex patterns
            excluded_path_patterns: List of excluded path regex patterns
            max_depth: Maximum crawl depth
            same_domain_only: Only discover URLs on same domain as base_url
        """
        self.base_url = base_url
        self.base_domain = get_domain(base_url)
        self.allowed_domains = set(allowed_domains or [self.base_domain])
        self.allowed_path_patterns = allowed_path_patterns or []
        self.excluded_path_patterns = excluded_path_patterns or []
        self.max_depth = max_depth
        self.same_domain_only = same_domain_only
        
        # Track discovered URLs to prevent loops
        self._discovered: Set[str] = set()
    
    def discover_links(
        self,
        html_content: str,
        current_url: str,
        current_depth: int = 0,
    ) -> List[str]:
        """
        Discover links from HTML content.
        
        Args:
            html_content: HTML content to parse
            current_url: URL of the current page (for resolving relative links)
            current_depth: Current crawl depth
            
        Returns:
            List of discovered, filtered, canonical URLs
        """
        if current_depth >= self.max_depth:
            logger.debug(f"Max depth {self.max_depth} reached, skipping link discovery")
            return []
        
        try:
            soup = BeautifulSoup(html_content, 'lxml')
        except Exception as e:
            logger.error(f"Failed to parse HTML: {e}")
            return []
        
        discovered_urls = []
        
        # Find all <a> tags with href
        links = soup.find_all('a', href=True)
        
        logger.debug(f"Found {len(links)} links in {current_url}")
        
        for link in links:
            href = link.get('href', '').strip()
            
            if not href or href.startswith(('#', 'javascript:', 'mailto:', 'tel:')):
                continue
            
            try:
                # Resolve relative URL
                absolute_url = urljoin(current_url, href)
                
                # Normalize and canonicalize
                canonical_url = normalize_url(absolute_url)
                
                # Apply filters
                if not self._should_include_url(canonical_url):
                    continue
                
                # Avoid duplicates
                if canonical_url in self._discovered:
                    continue
                
                self._discovered.add(canonical_url)
                discovered_urls.append(canonical_url)
                
            except Exception as e:
                logger.debug(f"Failed to process link '{href}': {e}")
                continue
        
        logger.info(f"Discovered {len(discovered_urls)} new URLs from {current_url}")
        
        return discovered_urls
    
    def _should_include_url(self, url: str) -> bool:
        """
        Check if URL should be included based on filters.
        
        Args:
            url: Canonicalized URL
            
        Returns:
            True if URL should be included
        """
        if not is_valid_url(url):
            return False
        
        parsed = urlparse(url)
        domain = parsed.netloc.lower()
        path = parsed.path
        
        # Check same domain restriction
        if self.same_domain_only:
            if not is_same_domain(url, self.base_url):
                logger.debug(f"Different domain: {domain} != {self.base_domain}")
                return False
        
        # Check allowed domains
        if self.allowed_domains:
            domain_allowed = False
            for allowed_domain in self.allowed_domains:
                if domain == allowed_domain or domain.endswith('.' + allowed_domain):
                    domain_allowed = True
                    break
            
            if not domain_allowed:
                logger.debug(f"Domain not in allowlist: {domain}")
                return False
        
        # Check excluded path patterns (takes precedence)
        if self.excluded_path_patterns:
            for pattern in self.excluded_path_patterns:
                if re.search(pattern, path):
                    logger.debug(f"Path matches exclusion pattern '{pattern}': {path}")
                    return False
        
        # Check allowed path patterns
        if self.allowed_path_patterns:
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
        """Reset discovered URLs tracking."""
        self._discovered.clear()
        logger.debug("URL discovery state reset")
    
    def get_discovered_count(self) -> int:
        """Get count of unique discovered URLs."""
        return len(self._discovered)

