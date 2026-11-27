"""robots.txt parser and checker using the shared SSRF-protected HTTPClient.

Policy (aligned with RFC 9309):
- When ``respect_robots_txt=False``: robots checks are disabled (no network I/O).
- When ``respect_robots_txt=True``:
  - Fetches ``<scheme>://<netloc>/robots.txt`` via ``HTTPClient`` (enforcing
    timeout, SSRF + DNS-rebinding protection, redirect validation, and size cap).
  - HTTP 200: parse rules and cache for ``cache_ttl_seconds``.
  - HTTP 404 / 410 (or other 4xx except 401/403): robots.txt not present -> allow.
  - HTTP 401 / 403: access denied -> disallow all paths on that origin.
  - HTTP 5xx / timeout / connection failure: fail closed with
    ``RobotsUnavailableError`` (do not silently crawl when server is unhealthy).
  - ``SecurityError``: always re-raised immediately (never fail-open on SSRF).
"""

import logging
import time
from dataclasses import dataclass
from typing import Dict, Optional
from urllib.parse import urlparse
from urllib.robotparser import RobotFileParser

from .exceptions import (
    ContentTooLargeError,
    HTTPError,
    RobotsDisallowedError,
    RobotsUnavailableError,
    SecurityError,
)
from .http_client import HTTPClient
from .job_context import JobContext

logger = logging.getLogger(__name__)


@dataclass
class _CacheEntry:
    parser: Optional[RobotFileParser]  # None => allow all (404/absent)
    disallow_all: bool
    fetched_at: float


class RobotsChecker:
    """robots.txt checker with TTL cache and SSRF-safe fetching."""

    def __init__(
        self,
        user_agent: str,
        http_client: Optional[HTTPClient] = None,
        *,
        respect_robots_txt: bool = True,
        cache_ttl_seconds: int = 3600,
        max_crawl_delay_seconds: float = 30.0,
    ):
        self.user_agent = user_agent
        self.http_client = http_client
        self.respect_robots_txt = respect_robots_txt
        self.cache_ttl_seconds = max(0, int(cache_ttl_seconds))
        self.max_crawl_delay_seconds = max(0.0, float(max_crawl_delay_seconds))
        self._cache: Dict[str, _CacheEntry] = {}

    def _get_robots_url(self, url: str) -> str:
        parsed = urlparse(url)
        scheme = parsed.scheme.lower()
        return f"{scheme}://{parsed.netloc}/robots.txt"

    def _get_entry(self, url: str, job_context: Optional[JobContext] = None) -> _CacheEntry:
        robots_url = self._get_robots_url(url)
        now = time.monotonic()

        cached = self._cache.get(robots_url)
        if cached is not None and (now - cached.fetched_at) < self.cache_ttl_seconds:
            return cached

        if self.http_client is None:
            raise RobotsUnavailableError(
                f"Cannot fetch {robots_url}: RobotsChecker has no HTTPClient configured"
            )

        try:
            content, _ctype, _lm = self.http_client.fetch(
                robots_url,
                respect_rate_limit=True,
                validate_security=True,
                allowed_content_types=None,
                job_context=job_context,
            )
            text = content.decode("utf-8", errors="replace")
            parser = RobotFileParser()
            parser.set_url(robots_url)
            parser.parse(text.splitlines())
            entry = _CacheEntry(parser=parser, disallow_all=False, fetched_at=now)
            self._cache[robots_url] = entry
            logger.info("Loaded robots.txt from %s", robots_url)
            return entry

        except SecurityError:
            # Never swallow SSRF / security violations
            raise

        except HTTPError as e:
            if e.status_code in (404, 410):
                logger.info("No robots.txt at %s (HTTP %d); allowing crawl", robots_url, e.status_code)
                entry = _CacheEntry(parser=None, disallow_all=False, fetched_at=now)
                self._cache[robots_url] = entry
                return entry
            if e.status_code in (401, 403):
                logger.warning("robots.txt forbidden at %s (HTTP %d); disallowing all", robots_url, e.status_code)
                entry = _CacheEntry(parser=None, disallow_all=True, fetched_at=now)
                self._cache[robots_url] = entry
                return entry
            if 400 <= e.status_code < 500:
                logger.warning("robots.txt returned HTTP %d at %s; treating as absent", e.status_code, robots_url)
                entry = _CacheEntry(parser=None, disallow_all=False, fetched_at=now)
                self._cache[robots_url] = entry
                return entry
            raise RobotsUnavailableError(
                f"Failed to fetch robots.txt from {robots_url}: {e}"
            ) from e

        except ContentTooLargeError as e:
            raise RobotsUnavailableError(
                f"robots.txt too large at {robots_url}: {e}"
            ) from e

    def is_allowed(self, url: str, job_context: Optional[JobContext] = None) -> bool:
        """Return True if ``url`` is allowed by robots.txt (or if robots checking is disabled)."""
        if not self.respect_robots_txt:
            return True

        entry = self._get_entry(url, job_context=job_context)
        if entry.disallow_all:
            return False
        if entry.parser is None:
            return True

        allowed = entry.parser.can_fetch(self.user_agent, url)
        if not allowed:
            logger.info("URL disallowed by robots.txt: %s", url)
        return allowed

    def get_crawl_delay(self, url: str, job_context: Optional[JobContext] = None) -> Optional[float]:
        """Return crawl-delay in seconds (capped at ``max_crawl_delay_seconds``), or None."""
        if not self.respect_robots_txt:
            return None

        entry = self._get_entry(url, job_context=job_context)
        if entry.parser is None:
            return None

        try:
            delay = entry.parser.crawl_delay(self.user_agent)
            if delay is None:
                return None
            delay_f = float(delay)
            if delay_f < 0:
                return None
            if self.max_crawl_delay_seconds > 0 and delay_f > self.max_crawl_delay_seconds:
                logger.warning(
                    "robots.txt crawl-delay %.1fs for %s capped at %.1fs",
                    delay_f, url, self.max_crawl_delay_seconds,
                )
                return self.max_crawl_delay_seconds
            return delay_f
        except (TypeError, ValueError):
            return None

    def check_and_raise(self, url: str, job_context: Optional[JobContext] = None) -> None:
        """Raise ``RobotsDisallowedError`` if ``url`` is disallowed by robots.txt."""
        if not self.is_allowed(url, job_context=job_context):
            raise RobotsDisallowedError(f"URL disallowed by robots.txt: {url}")

    def clear_cache(self) -> None:
        self._cache.clear()
