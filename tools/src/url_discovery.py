"""URL Discovery — extract, normalize, and filter links from HTML content.

Uses hostname-based domain matching (immune to port/userinfo/suffix confusion)
and pre-compiled regex filters. Empty ``allowed_path_patterns`` means allow all
paths not matched by ``excluded_path_patterns``.
"""

import logging
import re
from typing import List, Optional, Pattern, Set
from urllib.parse import urljoin, urlparse

from bs4 import BeautifulSoup

from .exceptions import ValidationError
from .url_utils import get_hostname, is_domain_allowed, is_same_domain, is_valid_url, normalize_url

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
        self.base_url = base_url
        self.base_domain = get_hostname(base_url)
        self.allowed_domains = list(allowed_domains) if allowed_domains is not None else [self.base_domain]
        self.allowed_path_patterns = list(allowed_path_patterns or [])
        self.excluded_path_patterns = list(excluded_path_patterns or [])
        self.max_depth = max_depth
        self.same_domain_only = same_domain_only

        self._compiled_allowed: List[Pattern[str]] = self._compile_patterns(
            self.allowed_path_patterns, "allowed_path_patterns"
        )
        self._compiled_excluded: List[Pattern[str]] = self._compile_patterns(
            self.excluded_path_patterns, "excluded_path_patterns"
        )
        self._discovered: Set[str] = set()

    @staticmethod
    def _compile_patterns(patterns: List[str], name: str) -> List[Pattern[str]]:
        compiled: List[Pattern[str]] = []
        for idx, pat in enumerate(patterns):
            try:
                compiled.append(re.compile(pat))
            except re.error as e:
                raise ValidationError(f"Invalid regex in {name}[{idx}] ({pat!r}): {e}") from e
        return compiled

    def discover_links(
        self,
        html_content: str,
        current_url: str,
        current_depth: int = 0,
    ) -> List[str]:
        """Discover canonical, filtered links from ``html_content``."""
        if current_depth >= self.max_depth:
            logger.debug("Max depth %d reached, skipping link discovery", self.max_depth)
            return []

        try:
            soup = BeautifulSoup(html_content, "lxml")
        except Exception:
            try:
                soup = BeautifulSoup(html_content, "html.parser")
            except Exception as e:
                logger.error("Failed to parse HTML for link discovery: %s", e)
                return []

        discovered_urls: List[str] = []
        links = soup.find_all("a", href=True)

        for link in links:
            href = (link.get("href") or "").strip()
            if not href or href.lower().startswith(("#", "javascript:", "mailto:", "tel:", "data:")):
                continue

            try:
                absolute_url = urljoin(current_url, href)
                canonical_url = normalize_url(absolute_url)
                if not self._should_include_url(canonical_url):
                    continue
                if canonical_url in self._discovered:
                    continue
                self._discovered.add(canonical_url)
                discovered_urls.append(canonical_url)
            except Exception as e:
                logger.debug("Skipping link %r: %s", href, e)
                continue

        logger.debug("Discovered %d new URL(s) from %s", len(discovered_urls), current_url)
        return discovered_urls

    def _should_include_url(self, url: str) -> bool:
        if not is_valid_url(url):
            return False

        hostname = get_hostname(url)
        path = urlparse(url).path

        if self.same_domain_only and not is_same_domain(url, self.base_url):
            return False

        if self.allowed_domains and not is_domain_allowed(hostname, self.allowed_domains):
            return False

        for pattern in self._compiled_excluded:
            if pattern.search(path):
                return False

        if self._compiled_allowed:
            if not any(pattern.search(path) for pattern in self._compiled_allowed):
                return False

        return True

    def reset(self) -> None:
        self._discovered.clear()

    def get_discovered_count(self) -> int:
        return len(self._discovered)
