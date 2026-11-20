"""
Tests for Source Registry module.
"""

import pytest
from datetime import datetime, timezone
from unittest.mock import MagicMock

from src.source_registry import SourceRegistry, CrawlSource
from src.exceptions import DatabaseError


@pytest.fixture
def mock_database():
    """Mock database instance."""
    db = MagicMock()
    return db


@pytest.fixture
def sample_source_row():
    """Sample database row for a crawl source."""
    return {
        'id': 1,
        'base_url': 'https://utc.edu.vn',
        'name': 'UTC Main Site',
        'sitemap_url': 'https://utc.edu.vn/sitemap.xml',
        'enabled': True,
        'rate_limit_seconds': 2.0,
        'allowed_path_patterns': ['/tuyen-sinh/.*', '/dao-tao/.*'],
        'excluded_path_patterns': ['/admin/.*'],
        'last_crawled_at': datetime(2024, 1, 15, 10, 30, 0, tzinfo=timezone.utc),
        'created_at': datetime(2024, 1, 1, 0, 0, 0, tzinfo=timezone.utc),
    }


class TestCrawlSource:
    """Tests for CrawlSource dataclass."""
    
    def test_valid_source(self, sample_source_row):
        """Test creating valid CrawlSource."""
        source = CrawlSource(**sample_source_row)
        
        assert source.id == 1
        assert source.base_url == 'https://utc.edu.vn'
        assert source.name == 'UTC Main Site'
        assert source.sitemap_url == 'https://utc.edu.vn/sitemap.xml'
        assert source.enabled is True
        assert source.rate_limit_seconds == 2.0
        assert len(source.allowed_path_patterns) == 2
        assert len(source.excluded_path_patterns) == 1
    
    def test_source_with_null_patterns(self, sample_source_row):
        """Test source with None patterns converts to empty lists."""
        sample_source_row['allowed_path_patterns'] = None
        sample_source_row['excluded_path_patterns'] = None
        
        source = CrawlSource(**sample_source_row)
        
        assert source.allowed_path_patterns == []
        assert source.excluded_path_patterns == []
    
    def test_source_missing_base_url(self, sample_source_row):
        """Test source without base_url raises error."""
        sample_source_row['base_url'] = ''
        
        with pytest.raises(ValueError, match="base_url cannot be empty"):
            CrawlSource(**sample_source_row)
    
    def test_source_negative_rate_limit(self, sample_source_row):
        """Test source with negative rate_limit raises error."""
        sample_source_row['rate_limit_seconds'] = -1.0
        
        with pytest.raises(ValueError, match="rate_limit_seconds must be >= 0"):
            CrawlSource(**sample_source_row)


class TestSourceRegistry:
    """Tests for SourceRegistry."""
    
    def test_init(self, mock_database):
        """Test SourceRegistry initialization."""
        registry = SourceRegistry(mock_database)
        assert registry.database is mock_database
    
    def test_get_enabled_sources_success(self, mock_database, sample_source_row):
        """Test getting enabled sources."""
        mock_database.execute_all.return_value = [sample_source_row]
        
        registry = SourceRegistry(mock_database)
        sources = registry.get_enabled_sources()
        
        assert len(sources) == 1
        assert sources[0].id == 1
        assert sources[0].base_url == 'https://utc.edu.vn'
        assert sources[0].enabled is True
        
        # Verify SQL query
        call_args = mock_database.execute_all.call_args
        query = call_args[0][0]
        assert 'FROM crawl_sources' in query
        assert 'WHERE enabled = true' in query
    
    def test_get_enabled_sources_empty(self, mock_database):
        """Test getting enabled sources when none exist."""
        mock_database.execute_all.return_value = []
        
        registry = SourceRegistry(mock_database)
        sources = registry.get_enabled_sources()
        
        assert len(sources) == 0
    
    def test_get_enabled_sources_skips_invalid(self, mock_database, sample_source_row):
        """Test that invalid sources are skipped."""
        invalid_row = sample_source_row.copy()
        invalid_row['base_url'] = ''  # Invalid
        
        mock_database.execute_all.return_value = [
            sample_source_row,
            invalid_row,  # This should be skipped
        ]
        
        registry = SourceRegistry(mock_database)
        sources = registry.get_enabled_sources()
        
        # Only valid source should be returned
        assert len(sources) == 1
        assert sources[0].id == 1
    
    def test_get_enabled_sources_database_error(self, mock_database):
        """Test database error when getting sources."""
        mock_database.execute_all.side_effect = Exception("Database connection failed")
        
        registry = SourceRegistry(mock_database)
        
        with pytest.raises(DatabaseError, match="Failed to get enabled sources"):
            registry.get_enabled_sources()
    
    def test_get_source_by_id_found(self, mock_database, sample_source_row):
        """Test getting source by ID when it exists."""
        mock_database.execute_one.return_value = sample_source_row
        
        registry = SourceRegistry(mock_database)
        source = registry.get_source_by_id(1)
        
        assert source is not None
        assert source.id == 1
        assert source.base_url == 'https://utc.edu.vn'
        
        # Verify query
        call_args = mock_database.execute_one.call_args
        query = call_args[0][0]
        assert 'WHERE id = %s' in query
        assert call_args[0][1] == (1,)
    
    def test_get_source_by_id_not_found(self, mock_database):
        """Test getting source by ID when it doesn't exist."""
        mock_database.execute_one.return_value = None
        
        registry = SourceRegistry(mock_database)
        source = registry.get_source_by_id(999)
        
        assert source is None
    
    def test_update_last_crawled(self, mock_database):
        """Test updating last_crawled_at timestamp."""
        registry = SourceRegistry(mock_database)
        registry.update_last_crawled(1)
        
        # Verify UPDATE query
        call_args = mock_database.execute.call_args
        query = call_args[0][0]
        assert 'UPDATE crawl_sources' in query
        assert 'SET last_crawled_at = clock_timestamp()' in query
        assert 'WHERE id = %s' in query
        assert call_args[0][1] == (1,)
    
    def test_update_last_crawled_database_error(self, mock_database):
        """Test database error when updating last_crawled."""
        mock_database.execute.side_effect = Exception("Database error")
        
        registry = SourceRegistry(mock_database)
        
        with pytest.raises(DatabaseError, match="Failed to update last_crawled"):
            registry.update_last_crawled(1)
    
    def test_validate_source_config_valid(self, sample_source_row):
        """Test validation of valid source config."""
        source = CrawlSource(**sample_source_row)
        registry = SourceRegistry(MagicMock())
        
        issues = registry.validate_source_config(source)
        
        assert len(issues) == 0
    
    def test_validate_source_config_invalid_base_url(self, sample_source_row):
        """Test validation catches invalid base_url."""
        sample_source_row['base_url'] = 'ftp://invalid.com'
        source = CrawlSource(**sample_source_row)
        registry = SourceRegistry(MagicMock())
        
        issues = registry.validate_source_config(source)
        
        assert len(issues) > 0
        assert any('must start with http' in issue for issue in issues)
    
    def test_validate_source_config_invalid_sitemap_url(self, sample_source_row):
        """Test validation catches invalid sitemap_url."""
        sample_source_row['sitemap_url'] = 'javascript:alert(1)'
        source = CrawlSource(**sample_source_row)
        registry = SourceRegistry(MagicMock())
        
        issues = registry.validate_source_config(source)
        
        assert len(issues) > 0
        assert any('sitemap_url must start with http' in issue for issue in issues)
    
    def test_validate_source_config_slow_rate_limit(self, sample_source_row):
        """Test validation warns about very slow rate limit."""
        sample_source_row['rate_limit_seconds'] = 120.0
        source = CrawlSource(**sample_source_row)
        registry = SourceRegistry(MagicMock())
        
        issues = registry.validate_source_config(source)
        
        assert len(issues) > 0
        assert any('very slow' in issue for issue in issues)
    
    def test_validate_source_config_no_discovery_method(self, sample_source_row):
        """Test validation warns when no discovery method available."""
        sample_source_row['sitemap_url'] = None
        sample_source_row['allowed_path_patterns'] = []
        source = CrawlSource(**sample_source_row)
        registry = SourceRegistry(MagicMock())
        
        issues = registry.validate_source_config(source)
        
        assert len(issues) > 0
        assert any("won't discover any URLs" in issue for issue in issues)
