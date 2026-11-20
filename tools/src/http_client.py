"""HTTP client with retry, timeout, and rate limiting.

Implements robust HTTP fetching per requirements:
- Timeouts
- Exponential backoff retry
- Rate limiting
- Content-type and size validation
- User-agent configuration
"""

import time
import logging
from typing import Optional, Dict
from urllib.parse import urlparse
import requests
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

from .exceptions import HTTPError, ContentTooLargeError, UnsupportedContentTypeError, SecurityError
from .config import CrawlerConfig
from .security_validator import SecurityValidator

logger = logging.getLogger(__name__)


# Supported content types
SUPPORTED_CONTENT_TYPES = {
    "text/html",
    "application/xhtml+xml",
    "application/pdf",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",  # .docx
    "application/msword",  # .doc (legacy, may not support)
}


class RateLimiter:
    """Simple rate limiter using token bucket algorithm."""

    def __init__(self, rate_limit_seconds: float):
        """Initialize rate limiter.

        Args:
            rate_limit_seconds: Minimum seconds between requests
        """
        self.rate_limit = rate_limit_seconds
        self.last_request_time: Dict[str, float] = {}

    def wait_if_needed(self, domain: str) -> None:
        """Wait if needed to respect rate limit for domain.

        Args:
            domain: Domain to check rate limit for
        """
        now = time.time()
        last_time = self.last_request_time.get(domain, 0.0)
        time_since_last = now - last_time

        if time_since_last < self.rate_limit:
            sleep_time = self.rate_limit - time_since_last
            logger.debug(f"Rate limiting {domain}: sleeping {sleep_time:.2f}s")
            time.sleep(sleep_time)

        self.last_request_time[domain] = time.time()


class HTTPClient:
    """HTTP client with retry, timeout, and rate limiting."""

    def __init__(self, config: CrawlerConfig):
        """Initialize HTTP client.

        Args:
            config: Crawler configuration
        """
        self.config = config
        self.rate_limiter = RateLimiter(config.default_rate_limit_seconds)
        self.security_validator = SecurityValidator(
            allowed_schemes=['http', 'https'],
            allow_private_ips=False,
        )

        # Configure session with retry
        self.session = requests.Session()

        # Retry strategy: retry on connection errors and 5xx
        retry_strategy = Retry(
            total=config.max_retries,
            backoff_factor=1,  # 1s, 2s, 4s
            status_forcelist=[429, 500, 502, 503, 504],
            allowed_methods=["GET", "HEAD"],
        )

        adapter = HTTPAdapter(max_retries=retry_strategy)
        self.session.mount("http://", adapter)
        self.session.mount("https://", adapter)

        # Set default headers
        self.session.headers.update(
            {
                "User-Agent": config.user_agent,
                "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document,*/*;q=0.8",
                "Accept-Language": "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7",
                "Accept-Encoding": "gzip, deflate",
                "Connection": "keep-alive",
            }
        )

    def fetch(
        self, url: str, respect_rate_limit: bool = True, validate_security: bool = True
    ) -> tuple[bytes, str, Optional[str]]:
        """Fetch content from URL.

        Args:
            url: URL to fetch
            respect_rate_limit: Whether to apply rate limiting
            validate_security: Whether to validate URL for SSRF/security issues

        Returns:
            Tuple of (content_bytes, content_type, last_modified)

        Raises:
            HTTPError: If request fails
            ContentTooLargeError: If content exceeds size limit
            UnsupportedContentTypeError: If content type not supported
            SecurityError: If URL fails security validation
        """
        # Security validation
        if validate_security:
            self.security_validator.validate_url(url)
        
        domain = urlparse(url).netloc

        # Rate limiting
        if respect_rate_limit:
            self.rate_limiter.wait_if_needed(domain)

        try:
            logger.info(f"Fetching: {url}")
            response = self.session.get(
                url,
                timeout=self.config.request_timeout_seconds,
                stream=True,  # Stream to check size before downloading
                allow_redirects=False,  # Handle redirects manually for validation
            )

            # Handle redirects manually to validate each redirect destination
            redirect_count = 0
            max_redirects = 5
            
            while response.is_redirect and redirect_count < max_redirects:
                redirect_url = response.headers.get('Location')
                
                if not redirect_url:
                    raise HTTPError(
                        response.status_code,
                        "Redirect without Location header",
                        url,
                    )
                
                # Resolve relative redirect URL
                from urllib.parse import urljoin
                redirect_url = urljoin(url, redirect_url)
                
                # Validate redirect destination
                if validate_security:
                    self.security_validator.validate_redirect(url, redirect_url)
                
                logger.info(f"Following redirect: {url} -> {redirect_url}")
                
                # Follow redirect
                url = redirect_url
                domain = urlparse(url).netloc
                
                if respect_rate_limit:
                    self.rate_limiter.wait_if_needed(domain)
                
                response = self.session.get(
                    url,
                    timeout=self.config.request_timeout_seconds,
                    stream=True,
                    allow_redirects=False,
                )
                
                redirect_count += 1
            
            if redirect_count >= max_redirects:
                raise HTTPError(
                    310,
                    f"Too many redirects (>{max_redirects})",
                    url,
                )

            # Check status
            if response.status_code != 200:
                raise HTTPError(
                    response.status_code,
                    f"Request failed: {response.reason}",
                    url,
                )

            # Check content type
            content_type = response.headers.get("Content-Type", "").split(";")[0].strip()
            if content_type not in SUPPORTED_CONTENT_TYPES:
                raise UnsupportedContentTypeError(
                    f"Unsupported content type: {content_type} ({url})"
                )

            # Check content length
            content_length = response.headers.get("Content-Length")
            max_size_bytes = self.config.max_content_size_mb * 1024 * 1024

            if content_length and int(content_length) > max_size_bytes:
                raise ContentTooLargeError(
                    f"Content too large: {content_length} bytes > {max_size_bytes} bytes ({url})"
                )

            # Download content with size check
            content = b""
            for chunk in response.iter_content(chunk_size=8192):
                content += chunk
                if len(content) > max_size_bytes:
                    raise ContentTooLargeError(
                        f"Content exceeded {max_size_bytes} bytes during download ({url})"
                    )

            # Get last modified header
            last_modified = response.headers.get("Last-Modified")

            logger.info(
                f"Fetched {len(content)} bytes from {url} (type: {content_type})"
            )

            return content, content_type, last_modified

        except requests.exceptions.Timeout as e:
            raise HTTPError(408, f"Request timeout: {e}", url) from e
        except requests.exceptions.ConnectionError as e:
            raise HTTPError(503, f"Connection error: {e}", url) from e
        except requests.exceptions.RequestException as e:
            raise HTTPError(500, f"Request failed: {e}", url) from e

    def head(self, url: str) -> Dict[str, str]:
        """Perform HEAD request to get headers without downloading content.

        Args:
            url: URL to check

        Returns:
            Response headers dict

        Raises:
            HTTPError: If request fails
        """
        try:
            response = self.session.head(
                url,
                timeout=self.config.request_timeout_seconds,
                allow_redirects=True,
            )

            if response.status_code not in (200, 301, 302, 304):
                raise HTTPError(
                    response.status_code,
                    f"HEAD request failed: {response.reason}",
                    url,
                )

            return dict(response.headers)

        except requests.exceptions.RequestException as e:
            raise HTTPError(500, f"HEAD request failed: {e}", url) from e

    def close(self) -> None:
        """Close HTTP session."""
        self.session.close()
