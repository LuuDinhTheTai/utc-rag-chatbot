"""Custom exceptions for UTC Crawler.

Exception taxonomy (used by the worker to decide what to do with a job):

* ``OwnershipLostError`` (``LeaseExpiredError``, ``ClaimTokenMismatchError``,
  ``JobStateError``): the worker no longer owns the job. It must stop at once and
  must NOT touch the job row again.
* ``ShutdownRequested``: graceful shutdown; the job is released back to the queue.
* ``PermanentJobError``: the job can never succeed as configured (missing source,
  invalid regex...). The job is failed without retry.
* ``DiscoveryError``: the website could not be discovered (unreachable, robots
  unavailable...). Retryable.
* URL-level errors (``HTTPError``, ``ParsingError``, ``SecurityError``...) are
  isolated to a single URL and recorded in the crawl report.
* Infrastructure errors (``DatabaseError``, ``EmbeddingError``) abort the job and
  are retried according to the retry policy.
"""


class CrawlerException(Exception):
    """Base exception for all crawler-related errors."""


class ConfigurationError(CrawlerException):
    """Configuration is invalid or missing required values."""


class HTTPError(CrawlerException):
    """HTTP request failed."""

    def __init__(self, status_code: int, message: str, url: str = ""):
        self.status_code = status_code
        self.url = url
        super().__init__(f"HTTP {status_code}: {message} ({url})")

    @property
    def is_transient(self) -> bool:
        """True for errors that may succeed on a later attempt."""
        return self.status_code in (408, 425, 429, 500, 502, 503, 504) or self.status_code == 0


class ContentTooLargeError(CrawlerException):
    """Content exceeds size limit."""


class UnsupportedContentTypeError(CrawlerException):
    """Content type is not supported."""


class ParsingError(CrawlerException):
    """Failed to parse document content."""


class EmptyContentError(ParsingError):
    """Document parsed successfully but contains no indexable text."""


class RobotsDisallowedError(CrawlerException):
    """URL disallowed by robots.txt."""


class RobotsUnavailableError(CrawlerException):
    """robots.txt could not be fetched (5xx / network). Per RFC 9309 crawling must stop."""


class OwnershipLostError(CrawlerException):
    """Base class: worker no longer owns the job."""


class LeaseExpiredError(OwnershipLostError):
    """Job lease expired, worker no longer owns the job."""


class ClaimTokenMismatchError(OwnershipLostError):
    """Claim token does not match expected value."""


class JobStateError(OwnershipLostError):
    """Job is not in RUNNING state any more (completed, failed or reset)."""


class ShutdownRequested(CrawlerException):
    """Worker received a shutdown signal while processing a job."""


class PermanentJobError(CrawlerException):
    """Job can never succeed with the current configuration; do not retry."""


class DiscoveryError(CrawlerException):
    """URL discovery failed (site unreachable, sitemap and homepage both failed)."""


class ChunkCountMismatchError(CrawlerException):
    """Chunk count verification failed."""


class DatabaseError(CrawlerException):
    """Database operation failed."""


class EmbeddingError(CrawlerException):
    """Ollama embedding generation failed."""


class ValidationError(CrawlerException):
    """Validation failed (URL, content, format, etc.)."""


class SecurityError(CrawlerException):
    """Security validation failed (SSRF, private IP, etc.)."""
