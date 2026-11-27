"""Sitemap Parser — discovers URLs from XML sitemaps and sitemap indexes.

Guarantees:
- SSRF protection is ALWAYS enabled when fetching sitemaps (including child
  sitemaps discovered inside a sitemap index).
- Sitemap URLs and discovered URLs are normalized via ``url_utils.normalize_url``.
- Domain filtering uses ``url_utils.is_domain_allowed`` (hostname-based, immune
  to port/userinfo/suffix confusion).
- Regex patterns are compiled and validated up front.
- Distinguishes:
  - Missing sitemap (HTTP 404/410 -> ``HTTPError`` with status_code 404/410)
  - Empty sitemap (valid XML with 0 URLs -> returns ``[]``)
  - Malformed/unreachable sitemap (raises ``ValidationError`` / ``HTTPError``)
- Respects ``max_urls``, ``max_sitemaps``, and ``JobContext.checkpoint()``.
"""

import logging
import re
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from datetime import datetime
from typing import List, Optional, Pattern, Set
from urllib.parse import urlparse

from .exceptions import HTTPError, OwnershipLostError, SecurityError, ShutdownRequested, ValidationError
from .http_client import HTTPClient
from .job_context import JobContext
from .url_utils import get_hostname, is_domain_allowed, is_valid_url, normalize_url

logger = logging.getLogger(__name__)

SITEMAP_NAMESPACES = {
    "sm": "http://www.sitemaps.org/schemas/sitemap/0.9",
    "xhtml": "http://www.w3.org/1999/xhtml",
    "image": "http://www.google.com/schemas/sitemap-image/1.1",
    "video": "http://www.google.com/schemas/sitemap-video/1.1",
}


@dataclass
class SitemapURL:
    """Represents a URL discovered from a sitemap."""

    loc: str
    lastmod: Optional[datetime]
    changefreq: Optional[str]
    priority: Optional[float]
    source_sitemap: str

    def __post_init__(self):
        if not self.loc:
            raise ValidationError("URL location cannot be empty")
        if not is_valid_url(self.loc):
            raise ValidationError(f"Invalid URL: {self.loc}")
        if self.priority is not None and not (0.0 <= self.priority <= 1.0):
            raise ValidationError(f"Priority must be between 0.0 and 1.0, got {self.priority}")


class SitemapParser:
    """Parses XML sitemaps and sitemap indexes."""

    def __init__(
        self,
        http_client: HTTPClient,
        allowed_domains: Optional[List[str]] = None,
        allowed_path_patterns: Optional[List[str]] = None,
        excluded_path_patterns: Optional[List[str]] = None,
        max_urls: int = 10000,
        max_sitemaps: int = 50,
    ):
        self.http_client = http_client
        self.allowed_domains = list(allowed_domains or [])
        self.allowed_path_patterns = list(allowed_path_patterns or [])
        self.excluded_path_patterns = list(excluded_path_patterns or [])
        self.max_urls = max_urls
        self.max_sitemaps = max_sitemaps

        self._compiled_allowed: List[Pattern[str]] = self._compile_patterns(
            self.allowed_path_patterns, "allowed_path_patterns"
        )
        self._compiled_excluded: List[Pattern[str]] = self._compile_patterns(
            self.excluded_path_patterns, "excluded_path_patterns"
        )

        self._discovered: Set[str] = set()
        self._processed_sitemaps: Set[str] = set()
        self.failed_child_sitemaps: List[str] = []

    @staticmethod
    def _compile_patterns(patterns: List[str], name: str) -> List[Pattern[str]]:
        compiled: List[Pattern[str]] = []
        for idx, pat in enumerate(patterns):
            try:
                compiled.append(re.compile(pat))
            except re.error as e:
                raise ValidationError(f"Invalid regex in {name}[{idx}] ({pat!r}): {e}") from e
        return compiled

    def parse_sitemap(
        self,
        sitemap_url: str,
        *,
        rate_limit_override: Optional[float] = None,
        job_context: Optional[JobContext] = None,
    ) -> List[SitemapURL]:
        """Parse a sitemap or sitemap index and return filtered, deduplicated URLs."""
        if job_context:
            job_context.checkpoint()

        try:
            canonical_sitemap_url = normalize_url(sitemap_url)
        except ValueError as e:
            raise ValidationError(f"Invalid sitemap URL {sitemap_url!r}: {e}") from e

        if not is_valid_url(canonical_sitemap_url):
            raise ValidationError(f"Invalid sitemap URL: {sitemap_url!r}")

        if self.allowed_domains and not is_domain_allowed(
            get_hostname(canonical_sitemap_url), self.allowed_domains
        ):
            raise ValidationError(
                f"Sitemap domain not in allowlist: {canonical_sitemap_url}"
            )

        if canonical_sitemap_url in self._processed_sitemaps:
            logger.debug("Sitemap already processed: %s", canonical_sitemap_url)
            return []

        if len(self._processed_sitemaps) >= self.max_sitemaps:
            logger.warning("Reached max_sitemaps limit (%d), skipping %s", self.max_sitemaps, canonical_sitemap_url)
            return []

        self._processed_sitemaps.add(canonical_sitemap_url)
        logger.info("Parsing sitemap: %s", canonical_sitemap_url)

        try:
            content_bytes, _content_type, _ = self.http_client.fetch(
                canonical_sitemap_url,
                respect_rate_limit=True,
                validate_security=True,
                allowed_content_types=None,
                rate_limit_override=rate_limit_override,
                job_context=job_context,
            )
            root = ET.fromstring(content_bytes)

            if self._is_sitemap_index(root):
                return self._parse_sitemap_index(
                    root,
                    canonical_sitemap_url,
                    rate_limit_override=rate_limit_override,
                    job_context=job_context,
                )
            return self._parse_sitemap_urls(root, canonical_sitemap_url)

        except (HTTPError, SecurityError, ValidationError, OwnershipLostError, ShutdownRequested):
            raise
        except ET.ParseError as e:
            raise ValidationError(f"Invalid XML in sitemap {canonical_sitemap_url}: {e}") from e
        except Exception as e:
            raise ValidationError(f"Failed to parse sitemap {canonical_sitemap_url}: {e}") from e

    def _is_sitemap_index(self, root: ET.Element) -> bool:
        if root.tag.endswith("sitemapindex"):
            return True
        if root.findall(".//sm:sitemap", SITEMAP_NAMESPACES):
            return True
        return bool(root.findall(".//sitemap"))

    def _parse_sitemap_index(
        self,
        root: ET.Element,
        index_url: str,
        *,
        rate_limit_override: Optional[float] = None,
        job_context: Optional[JobContext] = None,
    ) -> List[SitemapURL]:
        logger.info("Parsing sitemap index: %s", index_url)
        all_urls: List[SitemapURL] = []

        sitemaps = root.findall(".//sm:sitemap", SITEMAP_NAMESPACES)
        if not sitemaps:
            sitemaps = root.findall(".//sitemap")

        logger.info("Found %d child sitemap(s) in index %s", len(sitemaps), index_url)
        child_failures = 0

        for sitemap_elem in sitemaps:
            if job_context:
                job_context.checkpoint()
            if len(self._discovered) >= self.max_urls:
                logger.warning("Reached max_urls limit (%d), stopping sitemap index", self.max_urls)
                break
            if len(self._processed_sitemaps) >= self.max_sitemaps:
                logger.warning("Reached max_sitemaps limit (%d), stopping recursion", self.max_sitemaps)
                break

            loc_elem = sitemap_elem.find("sm:loc", SITEMAP_NAMESPACES)
            if loc_elem is None:
                loc_elem = sitemap_elem.find("loc")
            if loc_elem is None or not loc_elem.text or not loc_elem.text.strip():
                logger.warning("Sitemap entry missing <loc> in index %s", index_url)
                continue

            child_url = loc_elem.text.strip()
            try:
                child_urls = self.parse_sitemap(
                    child_url,
                    rate_limit_override=rate_limit_override,
                    job_context=job_context,
                )
                all_urls.extend(child_urls)
            except (OwnershipLostError, ShutdownRequested):
                raise
            except (SecurityError, HTTPError, ValidationError) as e:
                child_failures += 1
                self.failed_child_sitemaps.append(f"{child_url}: {e}")
                logger.error("Failed to parse child sitemap %s: %s", child_url, e)
                continue

        if sitemaps and child_failures == len(sitemaps) and not all_urls:
            raise ValidationError(
                f"All {child_failures} child sitemaps failed in index {index_url}"
            )

        return all_urls

    def _parse_sitemap_urls(self, root: ET.Element, sitemap_url: str) -> List[SitemapURL]:
        urls: List[SitemapURL] = []
        url_entries = root.findall(".//sm:url", SITEMAP_NAMESPACES)
        if not url_entries:
            url_entries = root.findall(".//url")

        logger.info("Found %d URL entries in sitemap %s", len(url_entries), sitemap_url)

        for url_elem in url_entries:
            if len(self._discovered) >= self.max_urls:
                logger.warning("Reached max_urls limit (%d), stopping", self.max_urls)
                break
            try:
                obj = self._parse_url_entry(url_elem, sitemap_url)
                if obj is None:
                    continue
                if not self._should_include_url(obj.loc):
                    continue
                if obj.loc in self._discovered:
                    continue
                self._discovered.add(obj.loc)
                urls.append(obj)
            except ValidationError as e:
                logger.warning("Invalid URL entry in sitemap %s: %s", sitemap_url, e)
                continue

        return urls

    def _parse_url_entry(self, url_elem: ET.Element, sitemap_url: str) -> Optional[SitemapURL]:
        loc_elem = url_elem.find("sm:loc", SITEMAP_NAMESPACES)
        if loc_elem is None:
            loc_elem = url_elem.find("loc")
        if loc_elem is None or not loc_elem.text or not loc_elem.text.strip():
            logger.warning("URL entry missing <loc> element")
            return None

        raw_loc = loc_elem.text.strip()
        try:
            loc = normalize_url(raw_loc)
        except Exception as e:
            logger.warning("Failed to normalize URL %s: %s", raw_loc, e)
            return None

        lastmod = None
        lastmod_elem = url_elem.find("sm:lastmod", SITEMAP_NAMESPACES)
        if lastmod_elem is None:
            lastmod_elem = url_elem.find("lastmod")
        if lastmod_elem is not None and lastmod_elem.text:
            lastmod = self._parse_datetime(lastmod_elem.text.strip())

        changefreq = None
        cf_elem = url_elem.find("sm:changefreq", SITEMAP_NAMESPACES)
        if cf_elem is None:
            cf_elem = url_elem.find("changefreq")
        if cf_elem is not None and cf_elem.text:
            changefreq = cf_elem.text.strip()

        priority = None
        pr_elem = url_elem.find("sm:priority", SITEMAP_NAMESPACES)
        if pr_elem is None:
            pr_elem = url_elem.find("priority")
        if pr_elem is not None and pr_elem.text:
            try:
                priority = float(pr_elem.text.strip())
            except ValueError:
                logger.warning("Invalid priority value: %s", pr_elem.text)

        return SitemapURL(
            loc=loc,
            lastmod=lastmod,
            changefreq=changefreq,
            priority=priority,
            source_sitemap=sitemap_url,
        )

    def _parse_datetime(self, dt_str: str) -> Optional[datetime]:
        formats = (
            "%Y-%m-%d",
            "%Y-%m-%dT%H:%M:%SZ",
            "%Y-%m-%dT%H:%M:%S%z",
            "%Y-%m-%dT%H:%M:%S.%fZ",
            "%Y-%m-%dT%H:%M:%S.%f%z",
        )
        for fmt in formats:
            try:
                return datetime.strptime(dt_str, fmt)
            except ValueError:
                continue
        logger.warning("Failed to parse datetime: %s", dt_str)
        return None

    def _should_include_url(self, url: str) -> bool:
        hostname = get_hostname(url)
        path = urlparse(url).path

        if self.allowed_domains and not is_domain_allowed(hostname, self.allowed_domains):
            logger.debug("Domain not in allowlist: %s", hostname)
            return False

        for pattern in self._compiled_excluded:
            if pattern.search(path):
                logger.debug("Path matches exclusion pattern '%s': %s", pattern.pattern, path)
                return False

        if self._compiled_allowed:
            if not any(pattern.search(path) for pattern in self._compiled_allowed):
                logger.debug("Path does not match any allowed pattern: %s", path)
                return False

        return True

    def reset(self) -> None:
        """Reset discovered URLs and processed sitemaps."""
        self._discovered.clear()
        self._processed_sitemaps.clear()
        self.failed_child_sitemaps.clear()
