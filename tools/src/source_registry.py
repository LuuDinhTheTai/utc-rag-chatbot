"""
Source Registry - Manages crawl sources from database.

Reads crawl_sources table, filters by enabled=true, provides source configuration
to crawler including sitemap_url, rate_limit_seconds, allowed/excluded path patterns.
"""

import logging
from typing import List, Optional
from dataclasses import dataclass
from datetime import datetime, timezone

from .database import Database
from .exceptions import DatabaseError


logger = logging.getLogger(__name__)


@dataclass
class CrawlSource:
    """Represents a crawl source configuration."""
    
    id: int
    base_url: str
    name: str
    sitemap_url: Optional[str]
    enabled: bool
    rate_limit_seconds: float
    allowed_path_patterns: List[str]
    excluded_path_patterns: List[str]
    last_crawled_at: Optional[datetime]
    created_at: datetime
    
    def __post_init__(self):
        """Validate source configuration."""
        if not self.base_url:
            raise ValueError(f"Source {self.id}: base_url cannot be empty")
        
        if self.rate_limit_seconds < 0:
            raise ValueError(f"Source {self.id}: rate_limit_seconds must be >= 0")
        
        # Ensure patterns are lists (handle None from database)
        if self.allowed_path_patterns is None:
            self.allowed_path_patterns = []
        if self.excluded_path_patterns is None:
            self.excluded_path_patterns = []


class SourceRegistry:
    """Manages crawl sources from database."""
    
    def __init__(self, database: Database):
        """
        Initialize source registry.
        
        Args:
            database: Database connection instance
        """
        self.database = database
    
    def get_enabled_sources(self) -> List[CrawlSource]:
        """
        Get all enabled crawl sources.
        
        Returns:
            List of enabled CrawlSource objects
            
        Raises:
            DatabaseError: If database query fails
        """
        try:
            query = """
                SELECT 
                    id,
                    base_url,
                    name,
                    sitemap_url,
                    enabled,
                    rate_limit_seconds,
                    allowed_path_patterns,
                    excluded_path_patterns,
                    last_crawled_at,
                    created_at
                FROM crawl_sources
                WHERE enabled = true
                ORDER BY id
            """
            
            rows = self.database.execute_all(query)
            
            sources = []
            for row in rows:
                try:
                    source = CrawlSource(
                        id=row["id"],
                        base_url=row["base_url"],
                        name=row["name"],
                        sitemap_url=row.get("sitemap_url"),
                        enabled=row["enabled"],
                        rate_limit_seconds=float(row.get("rate_limit_seconds", 2.0)),
                        allowed_path_patterns=row.get("allowed_path_patterns") or [],
                        excluded_path_patterns=row.get("excluded_path_patterns") or [],
                        last_crawled_at=row.get("last_crawled_at"),
                        created_at=row["created_at"],
                    )
                    sources.append(source)
                except (ValueError, TypeError) as e:
                    logger.error(f"Invalid source configuration for source {row.get('id')}: {e}")
                    # Skip invalid sources but continue processing others
                    continue
            
            logger.info(f"Loaded {len(sources)} enabled crawl sources")
            return sources
            
        except Exception as e:
            raise DatabaseError(f"Failed to get enabled sources: {e}") from e
    
    def get_source_by_id(self, source_id: int) -> Optional[CrawlSource]:
        """
        Get a specific crawl source by ID.
        
        Args:
            source_id: Source ID to retrieve
            
        Returns:
            CrawlSource object if found, None otherwise
            
        Raises:
            DatabaseError: If database query fails
        """
        try:
            query = """
                SELECT 
                    id,
                    base_url,
                    name,
                    sitemap_url,
                    enabled,
                    rate_limit_seconds,
                    allowed_path_patterns,
                    excluded_path_patterns,
                    last_crawled_at,
                    created_at
                FROM crawl_sources
                WHERE id = %s
            """
            
            row = self.database.execute_one(query, (source_id,))
            
            if not row:
                return None
            
            return CrawlSource(
                id=row["id"],
                base_url=row["base_url"],
                name=row["name"],
                sitemap_url=row.get("sitemap_url"),
                enabled=row["enabled"],
                rate_limit_seconds=float(row.get("rate_limit_seconds", 2.0)),
                allowed_path_patterns=row.get("allowed_path_patterns") or [],
                excluded_path_patterns=row.get("excluded_path_patterns") or [],
                last_crawled_at=row.get("last_crawled_at"),
                created_at=row["created_at"],
            )
            
        except Exception as e:
            raise DatabaseError(f"Failed to get source {source_id}: {e}") from e
    
    def update_last_crawled(self, source_id: int) -> None:
        """
        Update the last_crawled_at timestamp for a source.
        
        Args:
            source_id: Source ID to update
            
        Raises:
            DatabaseError: If database update fails
        """
        try:
            query = """
                UPDATE crawl_sources
                SET last_crawled_at = clock_timestamp()
                WHERE id = %s
            """
            
            self.database.execute(query, (source_id,))
            logger.info(f"Updated last_crawled_at for source {source_id}")
            
        except Exception as e:
            raise DatabaseError(f"Failed to update last_crawled for source {source_id}: {e}") from e
    
    def validate_source_config(self, source: CrawlSource) -> List[str]:
        """
        Validate source configuration and return list of issues.
        
        Args:
            source: CrawlSource to validate
            
        Returns:
            List of validation error messages (empty if valid)
        """
        issues = []
        
        # Check base_url format
        if not source.base_url.startswith(("http://", "https://")):
            issues.append(f"Source {source.id}: base_url must start with http:// or https://")
        
        # Check sitemap_url format if provided
        if source.sitemap_url and not source.sitemap_url.startswith(("http://", "https://")):
            issues.append(f"Source {source.id}: sitemap_url must start with http:// or https://")
        
        # Check rate limit is reasonable
        if source.rate_limit_seconds > 60:
            issues.append(f"Source {source.id}: rate_limit_seconds > 60 (very slow, verify this is intentional)")
        
        # Warn if no sitemap and no allowed patterns
        if not source.sitemap_url and not source.allowed_path_patterns:
            issues.append(
                f"Source {source.id}: No sitemap_url and no allowed_path_patterns "
                f"(crawler won't discover any URLs)"
            )
        
        return issues
