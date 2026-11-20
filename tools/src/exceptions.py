"""Custom exceptions for UTC Crawler.

Provides specific exception types for different failure scenarios.
"""


class CrawlerException(Exception):
    """Base exception for all crawler-related errors."""

    pass


class ConfigurationError(CrawlerException):
    """Configuration is invalid or missing required values."""

    pass


class HTTPError(CrawlerException):
    """HTTP request failed."""

    def __init__(self, status_code: int, message: str, url: str = ""):
        self.status_code = status_code
        self.url = url
        super().__init__(f"HTTP {status_code}: {message} ({url})")


class ContentTooLargeError(CrawlerException):
    """Content exceeds size limit."""

    pass


class UnsupportedContentTypeError(CrawlerException):
    """Content type is not supported."""

    pass


class ParsingError(CrawlerException):
    """Failed to parse document content."""

    pass


class RobotsDisallowedError(CrawlerException):
    """URL disallowed by robots.txt."""

    pass


class LeaseExpiredError(CrawlerException):
    """Job lease expired, worker no longer owns the job."""

    pass


class ClaimTokenMismatchError(CrawlerException):
    """Claim token does not match expected value."""

    pass


class ChunkCountMismatchError(CrawlerException):
    """Chunk count verification failed."""

    pass


class DatabaseError(CrawlerException):
    """Database operation failed."""

    pass


class EmbeddingError(CrawlerException):
    """Ollama embedding generation failed."""

    pass


class ValidationError(CrawlerException):
    """Validation failed (URL, content, format, etc.)."""

    pass


class SecurityError(CrawlerException):
    """Security validation failed (SSRF, private IP, etc.)."""

    pass
