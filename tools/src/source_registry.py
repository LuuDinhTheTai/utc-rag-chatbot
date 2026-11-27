"""Source Registry — loads and validates crawl_sources from PostgreSQL.

Rate limit precedence:
1. ``crawl_sources.rate_limit_seconds`` when not NULL.
2. ``default_rate_limit_seconds`` from ``CrawlerConfig``.
3. Safe fallback ``2.0`` seconds.
"""

import logging
import re
from dataclasses import dataclass, field
from typing import List, Optional

from .database import Database
from .exceptions import DatabaseError, ValidationError
from .url_utils import is_valid_url

logger = logging.getLogger(__name__)

SAFE_FALLBACK_RATE_LIMIT_SECONDS = 2.0


def _resolve_rate_limit(
    source_value: Optional[object],
    config_default: Optional[float] = None,
) -> float:
    """Resolve rate limit handling SQL NULL cleanly without float(None) crashes."""
    if source_value is not None:
        val = float(source_value)
        if val < 0:
            raise ValueError(f"rate_limit_seconds must be >= 0, got {val}")
        return val
    if config_default is not None and config_default >= 0:
        return float(config_default)
    return SAFE_FALLBACK_RATE_LIMIT_SECONDS


def validate_regex_patterns(patterns: List[str], field_name: str) -> List[str]:
    """Validate a list of regex patterns and return any syntax error messages."""
    errors: List[str] = []
    for idx, pattern in enumerate(patterns):
        try:
            re.compile(pattern)
        except re.error as e:
            errors.append(f"Invalid regex in {field_name}[{idx}] ({pattern!r}): {e}")
    return errors


@dataclass
class CrawlSource:
    """Represents a crawl source configuration."""

    id: int
    base_url: str
    sitemap_url: Optional[str]
    enabled: bool
    rate_limit_seconds: float
    allowed_path_patterns: List[str] = field(default_factory=list)
    excluded_path_patterns: List[str] = field(default_factory=list)
    crawl_interval_hours: Optional[int] = None

    def __post_init__(self):
        if not self.base_url or not self.base_url.strip():
            raise ValueError(f"Source {self.id}: base_url cannot be empty")
        if self.rate_limit_seconds is None:
            self.rate_limit_seconds = SAFE_FALLBACK_RATE_LIMIT_SECONDS
        else:
            self.rate_limit_seconds = float(self.rate_limit_seconds)
        if self.rate_limit_seconds < 0:
            raise ValueError(f"Source {self.id}: rate_limit_seconds must be >= 0")

        if self.allowed_path_patterns is None:
            self.allowed_path_patterns = []
        if self.excluded_path_patterns is None:
            self.excluded_path_patterns = []


class SourceRegistry:
    """Manages crawl sources from database."""

    def __init__(
        self,
        database: Database,
        default_rate_limit_seconds: float = SAFE_FALLBACK_RATE_LIMIT_SECONDS,
    ):
        self.database = database
        self.default_rate_limit_seconds = (
            float(default_rate_limit_seconds)
            if default_rate_limit_seconds is not None and default_rate_limit_seconds >= 0
            else SAFE_FALLBACK_RATE_LIMIT_SECONDS
        )

    def _row_to_source(self, row: dict) -> CrawlSource:
        rate_limit = _resolve_rate_limit(
            row.get("rate_limit_seconds"),
            self.default_rate_limit_seconds,
        )
        return CrawlSource(
            id=row["id"],
            base_url=row["base_url"],
            sitemap_url=row.get("sitemap_url"),
            enabled=row["enabled"],
            rate_limit_seconds=rate_limit,
            allowed_path_patterns=list(row.get("allowed_path_patterns") or []),
            excluded_path_patterns=list(row.get("excluded_path_patterns") or []),
            crawl_interval_hours=row.get("crawl_interval_hours"),
        )

    def get_enabled_sources(self) -> List[CrawlSource]:
        """Get all enabled crawl sources."""
        try:
            query = """
                SELECT
                    id,
                    base_url,
                    sitemap_url,
                    enabled,
                    rate_limit_seconds,
                    allowed_path_patterns,
                    excluded_path_patterns
                FROM crawl_sources
                WHERE enabled = true
                ORDER BY id
            """
            rows = self.database.execute(query)

            sources: List[CrawlSource] = []
            for row in rows:
                try:
                    sources.append(self._row_to_source(row))
                except (ValueError, TypeError) as e:
                    logger.error("Invalid source configuration for source %s: %s", row.get("id"), e)
                    continue

            logger.info("Loaded %d enabled crawl source(s)", len(sources))
            return sources
        except DatabaseError:
            raise
        except Exception as e:
            raise DatabaseError(f"Failed to get enabled sources: {e}") from e

    def get_source_by_id(self, source_id: int) -> Optional[CrawlSource]:
        """Get a specific crawl source by ID."""
        try:
            query = """
                SELECT
                    id,
                    base_url,
                    sitemap_url,
                    enabled,
                    rate_limit_seconds,
                    allowed_path_patterns,
                    excluded_path_patterns
                FROM crawl_sources
                WHERE id = %(source_id)s
            """
            row = self.database.execute_one(query, {"source_id": source_id})
            if not row:
                return None
            return self._row_to_source(row)
        except DatabaseError:
            raise
        except Exception as e:
            raise DatabaseError(f"Failed to get source {source_id}: {e}") from e

    def validate_source_config(self, source: CrawlSource) -> List[str]:
        """Validate source configuration and return list of error/warning strings.

        Empty ``allowed_path_patterns`` is valid and means "allow all paths
        (subject to excluded_path_patterns)".
        """
        issues: List[str] = []

        if not is_valid_url(source.base_url):
            issues.append(f"Source {source.id}: invalid base_url ({source.base_url!r})")

        if source.sitemap_url and not is_valid_url(source.sitemap_url):
            issues.append(f"Source {source.id}: invalid sitemap_url ({source.sitemap_url!r})")

        if source.rate_limit_seconds > 60:
            issues.append(
                f"Source {source.id}: rate_limit_seconds={source.rate_limit_seconds} > 60 "
                "(very slow, verify this is intentional)"
            )

        issues.extend(validate_regex_patterns(source.allowed_path_patterns, "allowed_path_patterns"))
        issues.extend(validate_regex_patterns(source.excluded_path_patterns, "excluded_path_patterns"))

        return issues

    def ensure_valid_patterns(self, source: CrawlSource) -> None:
        """Raise ``ValidationError`` if any regex pattern in ``source`` is invalid."""
        regex_errors = (
            validate_regex_patterns(source.allowed_path_patterns, "allowed_path_patterns")
            + validate_regex_patterns(source.excluded_path_patterns, "excluded_path_patterns")
        )
        if regex_errors:
            raise ValidationError("; ".join(regex_errors))
