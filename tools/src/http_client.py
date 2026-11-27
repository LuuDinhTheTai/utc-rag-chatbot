"""HTTP client with SSRF + DNS-rebinding protection, rate limiting, and safe retries.

Key guarantees
--------------
* **DNS-rebinding protection**: Before connecting, ``SecurityValidator`` resolves
  and validates every IP for the host. A thread-local DNS pin is installed around
  ``urllib3.util.connection.create_connection`` so the underlying socket connects
  ONLY to the pre-validated IP(s), while preserving the original hostname for
  HTTP ``Host`` and TLS SNI / certificate verification.
* **GET and HEAD symmetry**: Both ``fetch()`` and ``head()`` validate the initial
  URL and every redirect hop manually (no automatic redirects), detect redirect
  loops, enforce ``max_redirects`` without off-by-one errors, apply rate limiting,
  and close every response in a ``finally`` block.
* **Safe retries**: Retries only transient failures (429, 500, 502, 503, 504,
  connection/timeout errors), caps ``Retry-After`` at ``max_retry_after_seconds``,
  and checks ``JobContext.checkpoint()`` so retries cannot outlive a job lease.
* **Streaming size limit**: Uses ``bytearray`` (no quadratic ``bytes +=`` copies),
  tolerates malformed ``Content-Length`` headers, and enforces ``max_content_size_mb``.
* **Strict content types**: Legacy ``.doc`` (``application/msword``) is NOT in
  ``SUPPORTED_CONTENT_TYPES`` and is rejected explicitly.
"""

import logging
import socket
import threading
import time
from contextlib import contextmanager
from email.utils import parsedate_to_datetime
from typing import Collection, Dict, List, Optional, Set, Tuple
from urllib.parse import urljoin, urlparse

import requests
import urllib3.util.connection as urllib3_cn

from .config import CrawlerConfig
from .exceptions import (
    ContentTooLargeError,
    HTTPError,
    SecurityError,
    UnsupportedContentTypeError,
)
from .job_context import JobContext
from .security_validator import SecurityValidator
from .url_utils import get_hostname

logger = logging.getLogger(__name__)

# Supported document content types (legacy application/msword .doc is explicitly excluded)
SUPPORTED_CONTENT_TYPES: frozenset[str] = frozenset({
    "text/html",
    "application/xhtml+xml",
    "application/pdf",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
})

XML_CONTENT_TYPES: frozenset[str] = frozenset({
    "application/xml",
    "text/xml",
    "application/rss+xml",
    "application/atom+xml",
    "text/plain",
})

TEXT_CONTENT_TYPES: frozenset[str] = frozenset({
    "text/plain",
    "text/html",
})

TRANSIENT_STATUS_CODES: frozenset[int] = frozenset({429, 500, 502, 503, 504})

# ---------------------------------------------------------------------------
# DNS-rebinding protection: pin urllib3 connection targets to validated IPs
# ---------------------------------------------------------------------------
_dns_pin_local = threading.local()
_orig_create_connection = urllib3_cn.create_connection
_patch_lock = threading.Lock()
_patched = False


def _pinned_create_connection(address, timeout=socket._GLOBAL_DEFAULT_TIMEOUT, source_address=None, socket_options=None):
    host, port = address
    pin: Optional[Dict[str, List[str]]] = getattr(_dns_pin_local, "pins", None)
    if pin:
        key = host.strip().rstrip(".").lower()
        validated_ips = pin.get(key)
        if validated_ips:
            last_err: Optional[Exception] = None
            for ip in validated_ips:
                try:
                    return _orig_create_connection(
                        (ip, port),
                        timeout=timeout,
                        source_address=source_address,
                        socket_options=socket_options,
                    )
                except OSError as e:
                    last_err = e
                    continue
            if last_err is not None:
                raise last_err
    return _orig_create_connection(
        address, timeout=timeout, source_address=source_address, socket_options=socket_options
    )


def _ensure_dns_pinning_installed() -> None:
    global _patched
    if not _patched:
        with _patch_lock:
            if not _patched:
                urllib3_cn.create_connection = _pinned_create_connection
                _patched = True


@contextmanager
def _pin_host_ips(hostname: str, ips: List[str]):
    """Bind ``hostname`` to ``ips`` for this thread's outgoing urllib3 sockets."""
    if not ips:
        yield
        return
    _ensure_dns_pinning_installed()
    prev = getattr(_dns_pin_local, "pins", None)
    merged = dict(prev) if prev else {}
    merged[hostname.strip().rstrip(".").lower()] = list(ips)
    _dns_pin_local.pins = merged
    try:
        yield
    finally:
        _dns_pin_local.pins = prev


# ---------------------------------------------------------------------------
# Thread-safe per-domain rate limiter
# ---------------------------------------------------------------------------
class RateLimiter:
    """Thread-safe per-domain rate limiter with optional per-request delay override."""

    def __init__(self, default_rate_limit_seconds: float):
        self.rate_limit = max(0.0, float(default_rate_limit_seconds))
        self._last_request_time: Dict[str, float] = {}
        self._lock = threading.Lock()

    def wait_if_needed(
        self,
        domain: str,
        min_interval: Optional[float] = None,
        job_context: Optional[JobContext] = None,
    ) -> None:
        """Reserve a request slot for ``domain`` and sleep outside the lock if needed."""
        interval = self.rate_limit if min_interval is None else max(0.0, float(min_interval))
        if interval <= 0:
            if job_context:
                job_context.checkpoint()
            return

        key = domain.lower()
        with self._lock:
            now = time.monotonic()
            last = self._last_request_time.get(key, 0.0)
            target = max(now, last + interval)
            sleep_time = target - now
            # Reserve the slot immediately so concurrent callers for the same domain
            # are serialized rather than firing simultaneously.
            self._last_request_time[key] = target

        if sleep_time > 0:
            logger.debug("Rate limiting %s: sleeping %.2fs", key, sleep_time)
            if job_context:
                job_context.sleep(sleep_time)
            else:
                time.sleep(sleep_time)


def _parse_retry_after(header_value: Optional[str], max_seconds: float) -> Optional[float]:
    """Parse HTTP Retry-After header (delta-seconds or HTTP-date), capped at ``max_seconds``."""
    if not header_value:
        return None
    val = header_value.strip()
    try:
        seconds = float(val)
        return max(0.0, min(seconds, max_seconds))
    except ValueError:
        pass
    try:
        dt = parsedate_to_datetime(val)
        delta = dt.timestamp() - time.time()
        return max(0.0, min(delta, max_seconds))
    except (TypeError, ValueError, OverflowError):
        return None


def _parse_content_length(header_value: Optional[str]) -> Optional[int]:
    """Safely parse Content-Length; return None if absent or malformed."""
    if not header_value:
        return None
    raw = header_value.strip()
    try:
        val = int(raw)
        return val if val >= 0 else None
    except ValueError:
        logger.warning("Ignoring malformed Content-Length header: %r", raw)
        return None


class HTTPClient:
    """HTTP client with SSRF/DNS-rebinding protection, rate limiting, and bounded retries."""

    def __init__(
        self,
        config: CrawlerConfig,
        security_validator: Optional[SecurityValidator] = None,
    ):
        self.config = config
        self.rate_limiter = RateLimiter(config.default_rate_limit_seconds)
        self.security_validator = security_validator or SecurityValidator(
            allowed_schemes=["http", "https"],
            allow_private_ips=False,
            resolve_dns=True,
        )

        # We manage retries ourselves so every retry respects JobContext, caps
        # Retry-After, and re-validates redirects/DNS cleanly.
        # Disable trust_env so implicit HTTP_PROXY/HTTPS_PROXY/.netrc cannot
        # bypass local DNS pinning / SSRF protection.
        self.session = requests.Session()
        self.session.trust_env = False
        self.session.headers.update({
            "User-Agent": config.user_agent,
            "Accept": (
                "text/html,application/xhtml+xml,application/xml;q=0.9,"
                "application/pdf,"
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document,"
                "*/*;q=0.8"
            ),
            "Accept-Language": "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7",
            "Accept-Encoding": "gzip, deflate",
            "Connection": "keep-alive",
        })

    def _sleep(self, seconds: float, job_context: Optional[JobContext]) -> None:
        if seconds <= 0:
            return
        if job_context:
            job_context.sleep(seconds)
        else:
            time.sleep(seconds)

    def _send_with_retries(
        self,
        method: str,
        url: str,
        *,
        stream: bool,
        respect_rate_limit: bool,
        rate_limit_override: Optional[float],
        validate_security: bool,
        job_context: Optional[JobContext],
    ) -> requests.Response:
        """Send a single HTTP hop (no auto-redirects) with SSRF/DNS pinning and retries."""
        if job_context:
            job_context.checkpoint()

        hostname = ""
        validated_ips: List[str] = []
        if validate_security:
            hostname, validated_ips = self.security_validator.validate_and_resolve(url)

        domain = hostname or get_hostname(url) or urlparse(url).netloc.lower()
        max_attempts = 1 + max(0, self.config.max_retries)
        last_error: Optional[Exception] = None

        for attempt in range(max_attempts):
            if job_context:
                job_context.checkpoint()
            if respect_rate_limit:
                self.rate_limiter.wait_if_needed(
                    domain, min_interval=rate_limit_override, job_context=job_context
                )

            try:
                with _pin_host_ips(hostname, validated_ips):
                    response = self.session.request(
                        method=method,
                        url=url,
                        timeout=self.config.request_timeout_seconds,
                        stream=stream,
                        allow_redirects=False,
                    )
            except requests.exceptions.Timeout as e:
                last_error = HTTPError(408, f"Request timeout: {e}", url)
                if attempt + 1 < max_attempts:
                    delay = min(2 ** attempt, self.config.max_retry_after_seconds)
                    logger.warning("%s %s timed out (attempt %d/%d), retrying in %ss",
                                   method, url, attempt + 1, max_attempts, delay)
                    self._sleep(delay, job_context)
                    continue
                raise last_error from e
            except requests.exceptions.ConnectionError as e:
                last_error = HTTPError(503, f"Connection error: {e}", url)
                if attempt + 1 < max_attempts:
                    delay = min(2 ** attempt, self.config.max_retry_after_seconds)
                    logger.warning("%s %s connection error (attempt %d/%d), retrying in %ss",
                                   method, url, attempt + 1, max_attempts, delay)
                    self._sleep(delay, job_context)
                    continue
                raise last_error from e
            except requests.exceptions.RequestException as e:
                raise HTTPError(500, f"Request failed: {e}", url) from e

            # Transient HTTP status -> close and retry if attempts remain
            if response.status_code in TRANSIENT_STATUS_CODES and attempt + 1 < max_attempts:
                retry_after = _parse_retry_after(
                    response.headers.get("Retry-After"),
                    self.config.max_retry_after_seconds,
                )
                delay = retry_after if retry_after is not None else min(
                    float(2 ** attempt), float(self.config.max_retry_after_seconds)
                )
                status = response.status_code
                response.close()
                logger.warning(
                    "%s %s returned HTTP %d (attempt %d/%d), retrying in %.1fs",
                    method, url, status, attempt + 1, max_attempts, delay,
                )
                self._sleep(delay, job_context)
                continue

            return response

        assert last_error is not None
        raise last_error

    def _follow_redirects(
        self,
        method: str,
        url: str,
        *,
        stream: bool,
        respect_rate_limit: bool,
        rate_limit_override: Optional[float],
        validate_security: bool,
        job_context: Optional[JobContext],
    ) -> Tuple[requests.Response, str]:
        """Execute ``method`` following redirects manually with full security validation."""
        current_url = url
        visited: Set[str] = {current_url}
        max_redirects = self.config.max_redirects

        response = self._send_with_retries(
            method,
            current_url,
            stream=stream,
            respect_rate_limit=respect_rate_limit,
            rate_limit_override=rate_limit_override,
            validate_security=validate_security,
            job_context=job_context,
        )

        redirects = 0
        while response.is_redirect or response.status_code in (301, 302, 303, 307, 308):
            location = response.headers.get("Location")
            status = response.status_code
            response.close()

            if not location:
                raise HTTPError(status, "Redirect without Location header", current_url)

            if redirects >= max_redirects:
                raise HTTPError(310, f"Too many redirects (>{max_redirects})", current_url)

            next_url = urljoin(current_url, location.strip())
            if next_url in visited:
                raise HTTPError(310, f"Redirect loop detected: {current_url} -> {next_url}", next_url)
            visited.add(next_url)

            if validate_security:
                self.security_validator.validate_redirect(current_url, next_url)

            logger.info("Following redirect (%d/%d): %s -> %s", redirects + 1, max_redirects, current_url, next_url)
            current_url = next_url
            redirects += 1

            response = self._send_with_retries(
                method,
                current_url,
                stream=stream,
                respect_rate_limit=respect_rate_limit,
                rate_limit_override=rate_limit_override,
                validate_security=validate_security,
                job_context=job_context,
            )

        return response, current_url

    def fetch(
        self,
        url: str,
        respect_rate_limit: bool = True,
        validate_security: bool = True,
        *,
        allowed_content_types: Optional[Collection[str]] = SUPPORTED_CONTENT_TYPES,
        rate_limit_override: Optional[float] = None,
        job_context: Optional[JobContext] = None,
    ) -> Tuple[bytes, str, Optional[str]]:
        """Fetch content from ``url`` with SSRF protection, redirect validation, and size limits.

        Args:
            allowed_content_types: Collection of allowed MIME types, or ``None`` to accept any.
        """
        logger.info("Fetching: %s", url)
        response, final_url = self._follow_redirects(
            "GET",
            url,
            stream=True,
            respect_rate_limit=respect_rate_limit,
            rate_limit_override=rate_limit_override,
            validate_security=validate_security,
            job_context=job_context,
        )

        try:
            if response.status_code != 200:
                raise HTTPError(
                    response.status_code,
                    f"Request failed: {response.reason}",
                    final_url,
                )

            content_type = response.headers.get("Content-Type", "").split(";", 1)[0].strip().lower()
            if allowed_content_types is not None and content_type not in allowed_content_types:
                raise UnsupportedContentTypeError(
                    f"Unsupported content type: '{content_type}' ({final_url})"
                )

            max_size_bytes = self.config.max_content_size_mb * 1024 * 1024
            content_length = _parse_content_length(response.headers.get("Content-Length"))
            if content_length is not None and content_length > max_size_bytes:
                raise ContentTooLargeError(
                    f"Content too large: {content_length} bytes > {max_size_bytes} bytes ({final_url})"
                )

            buf = bytearray()
            for chunk in response.iter_content(chunk_size=16384):
                if job_context:
                    job_context.checkpoint()
                if not chunk:
                    continue
                if len(buf) + len(chunk) > max_size_bytes:
                    raise ContentTooLargeError(
                        f"Content exceeded {max_size_bytes} bytes during download ({final_url})"
                    )
                buf.extend(chunk)

            last_modified = response.headers.get("Last-Modified")
            logger.info("Fetched %d bytes from %s (type: %s)", len(buf), final_url, content_type)
            return bytes(buf), content_type, last_modified
        finally:
            response.close()

    def head(
        self,
        url: str,
        respect_rate_limit: bool = True,
        validate_security: bool = True,
        *,
        rate_limit_override: Optional[float] = None,
        job_context: Optional[JobContext] = None,
    ) -> Dict[str, str]:
        """Perform a HEAD request with the same SSRF, redirect, and rate-limit protections as GET."""
        response, final_url = self._follow_redirects(
            "HEAD",
            url,
            stream=False,
            respect_rate_limit=respect_rate_limit,
            rate_limit_override=rate_limit_override,
            validate_security=validate_security,
            job_context=job_context,
        )
        try:
            if response.status_code not in (200, 304):
                raise HTTPError(
                    response.status_code,
                    f"HEAD request failed: {response.reason}",
                    final_url,
                )
            return dict(response.headers)
        finally:
            response.close()

    def close(self) -> None:
        """Close HTTP session."""
        self.session.close()
