"""Tests for SourceRegistry — schema alignment, NULL rate limit handling, regex validation."""

from unittest.mock import MagicMock

import pytest

from src.exceptions import DatabaseError, ValidationError
from src.source_registry import CrawlSource, SourceRegistry


@pytest.fixture
def mock_database():
    return MagicMock()


@pytest.fixture
def sample_source_row():
    return {
        "id": 1,
        "base_url": "https://utc.edu.vn",
        "sitemap_url": "https://utc.edu.vn/sitemap.xml",
        "enabled": True,
        "rate_limit_seconds": 2.0,
        "allowed_path_patterns": ["/tuyen-sinh/.*", "/dao-tao/.*"],
        "excluded_path_patterns": ["/admin/.*"],
    }


class TestCrawlSource:
    def test_valid_source(self, sample_source_row):
        source = CrawlSource(**sample_source_row)
        assert source.id == 1
        assert source.base_url == "https://utc.edu.vn"
        assert source.rate_limit_seconds == 2.0
        assert len(source.allowed_path_patterns) == 2

    def test_source_with_null_patterns_and_rate_limit(self, sample_source_row):
        sample_source_row["allowed_path_patterns"] = None
        sample_source_row["excluded_path_patterns"] = None
        sample_source_row["rate_limit_seconds"] = None
        source = CrawlSource(**sample_source_row)
        assert source.allowed_path_patterns == []
        assert source.excluded_path_patterns == []
        assert source.rate_limit_seconds == 2.0

    def test_invalid_source(self, sample_source_row):
        bad1 = dict(sample_source_row, base_url="")
        with pytest.raises(ValueError, match="base_url cannot be empty"):
            CrawlSource(**bad1)

        bad2 = dict(sample_source_row, rate_limit_seconds=-1.0)
        with pytest.raises(ValueError, match="rate_limit_seconds must be >= 0"):
            CrawlSource(**bad2)


class TestSourceRegistry:
    def test_get_enabled_sources_success(self, mock_database, sample_source_row):
        mock_database.execute.return_value = [sample_source_row]
        registry = SourceRegistry(mock_database, default_rate_limit_seconds=3.5)
        sources = registry.get_enabled_sources()
        assert len(sources) == 1
        assert sources[0].rate_limit_seconds == 2.0
        query = mock_database.execute.call_args[0][0]
        assert "FROM crawl_sources" in query
        assert "name" not in query.lower()

    def test_get_enabled_sources_null_rate_limit_uses_config_default(self, mock_database, sample_source_row):
        row = dict(sample_source_row, rate_limit_seconds=None)
        mock_database.execute.return_value = [row]
        registry = SourceRegistry(mock_database, default_rate_limit_seconds=4.5)
        sources = registry.get_enabled_sources()
        assert sources[0].rate_limit_seconds == 4.5

    def test_get_enabled_sources_skips_invalid(self, mock_database, sample_source_row):
        invalid_row = dict(sample_source_row, base_url="")
        mock_database.execute.return_value = [sample_source_row, invalid_row]
        registry = SourceRegistry(mock_database)
        sources = registry.get_enabled_sources()
        assert len(sources) == 1

    def test_get_enabled_sources_database_error(self, mock_database):
        mock_database.execute.side_effect = Exception("Database connection failed")
        registry = SourceRegistry(mock_database)
        with pytest.raises(DatabaseError, match="Failed to get enabled sources"):
            registry.get_enabled_sources()

    def test_get_source_by_id_found_and_not_found(self, mock_database, sample_source_row):
        mock_database.execute_one.return_value = sample_source_row
        registry = SourceRegistry(mock_database)
        source = registry.get_source_by_id(1)
        assert source is not None and source.id == 1

        mock_database.execute_one.return_value = None
        assert registry.get_source_by_id(999) is None

    def test_validate_source_config(self, sample_source_row):
        registry = SourceRegistry(MagicMock())
        # Valid config
        assert registry.validate_source_config(CrawlSource(**sample_source_row)) == []

        # Empty allowed_path_patterns + no sitemap is VALID (means allow all paths via homepage)
        empty_allowed = dict(sample_source_row, sitemap_url=None, allowed_path_patterns=[])
        assert registry.validate_source_config(CrawlSource(**empty_allowed)) == []

        # Invalid base_url
        issues = registry.validate_source_config(CrawlSource(**dict(sample_source_row, base_url="ftp://x.com")))
        assert any("invalid base_url" in i for i in issues)

        # Invalid regex reported clearly
        bad_regex = CrawlSource(**dict(sample_source_row, allowed_path_patterns=["[unclosed"]))
        issues = registry.validate_source_config(bad_regex)
        assert any("Invalid regex" in i for i in issues)
        with pytest.raises(ValidationError, match="Invalid regex"):
            registry.ensure_valid_patterns(bad_regex)
