"""
Tests for Sitemap Parser module.
"""

import pytest
from datetime import datetime
from unittest.mock import MagicMock, Mock
from dataclasses import dataclass

from src.sitemap_parser import SitemapParser, SitemapURL, SITEMAP_NAMESPACES
from src.exceptions import HTTPError, ValidationError


@dataclass
class MockResponse:
    """Mock HTTP response."""
    content: bytes
    status_code: int = 200


@pytest.fixture
def mock_http_client():
    """Mock HTTP client."""
    return MagicMock()


@pytest.fixture
def simple_sitemap_xml():
    """Simple sitemap XML."""
    return b'''<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
    <url>
        <loc>https://utc.edu.vn/tuyen-sinh</loc>
        <lastmod>2024-01-15</lastmod>
        <changefreq>weekly</changefreq>
        <priority>0.8</priority>
    </url>
    <url>
        <loc>https://utc.edu.vn/dao-tao</loc>
        <lastmod>2024-01-10T10:30:00Z</lastmod>
    </url>
</urlset>'''


@pytest.fixture
def sitemap_index_xml():
    """Sitemap index XML."""
    return b'''<?xml version="1.0" encoding="UTF-8"?>
<sitemapindex xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
    <sitemap>
        <loc>https://utc.edu.vn/sitemap-1.xml</loc>
    </sitemap>
    <sitemap>
        <loc>https://utc.edu.vn/sitemap-2.xml</loc>
    </sitemap>
</sitemapindex>'''


class TestSitemapURL:
    """Tests for SitemapURL dataclass."""
    
    def test_valid_sitemap_url(self):
        """Test creating valid SitemapURL."""
        url = SitemapURL(
            loc='https://utc.edu.vn/page',
            lastmod=datetime(2024, 1, 15),
            changefreq='weekly',
            priority=0.8,
            source_sitemap='https://utc.edu.vn/sitemap.xml'
        )
        
        assert url.loc == 'https://utc.edu.vn/page'
        assert url.priority == 0.8
    
    def test_sitemap_url_empty_loc(self):
        """Test SitemapURL with empty loc raises error."""
        with pytest.raises(ValidationError, match="URL location cannot be empty"):
            SitemapURL(
                loc='',
                lastmod=None,
                changefreq=None,
                priority=None,
                source_sitemap='https://utc.edu.vn/sitemap.xml'
            )
    
    def test_sitemap_url_invalid_url(self):
        """Test SitemapURL with invalid URL raises error."""
        with pytest.raises(ValidationError, match="Invalid URL"):
            SitemapURL(
                loc='not-a-url',
                lastmod=None,
                changefreq=None,
                priority=None,
                source_sitemap='https://utc.edu.vn/sitemap.xml'
            )
    
    def test_sitemap_url_invalid_priority(self):
        """Test SitemapURL with invalid priority raises error."""
        with pytest.raises(ValidationError, match="Priority must be between"):
            SitemapURL(
                loc='https://utc.edu.vn/page',
                lastmod=None,
                changefreq=None,
                priority=1.5,  # Invalid: > 1.0
                source_sitemap='https://utc.edu.vn/sitemap.xml'
            )


class TestSitemapParser:
    """Tests for SitemapParser."""
    
    def test_init(self, mock_http_client):
        """Test SitemapParser initialization."""
        parser = SitemapParser(
            http_client=mock_http_client,
            allowed_domains=['utc.edu.vn'],
            allowed_path_patterns=['/tuyen-sinh/.*'],
            excluded_path_patterns=['/admin/.*'],
            max_urls=5000
        )
        
        assert parser.http_client is mock_http_client
        assert 'utc.edu.vn' in parser.allowed_domains
        assert len(parser.allowed_path_patterns) == 1
        assert len(parser.excluded_path_patterns) == 1
        assert parser.max_urls == 5000
    
    def test_parse_simple_sitemap(self, mock_http_client, simple_sitemap_xml):
        """Test parsing simple sitemap."""
        mock_http_client.get.return_value = MockResponse(content=simple_sitemap_xml)
        
        parser = SitemapParser(mock_http_client, allowed_domains=['utc.edu.vn'])
        urls = parser.parse_sitemap('https://utc.edu.vn/sitemap.xml')
        
        assert len(urls) == 2
        assert urls[0].loc == 'https://utc.edu.vn/tuyen-sinh'
        assert urls[0].priority == 0.8
        assert urls[0].changefreq == 'weekly'
        assert urls[1].loc == 'https://utc.edu.vn/dao-tao'
    
    def test_parse_sitemap_with_lastmod(self, mock_http_client, simple_sitemap_xml):
        """Test parsing sitemap with lastmod dates."""
        mock_http_client.get.return_value = MockResponse(content=simple_sitemap_xml)
        
        parser = SitemapParser(mock_http_client, allowed_domains=['utc.edu.vn'])
        urls = parser.parse_sitemap('https://utc.edu.vn/sitemap.xml')
        
        # First URL has simple date format
        assert urls[0].lastmod is not None
        assert urls[0].lastmod.year == 2024
        assert urls[0].lastmod.month == 1
        assert urls[0].lastmod.day == 15
        
        # Second URL has ISO datetime format
        assert urls[1].lastmod is not None
    
    def test_parse_sitemap_index(self, mock_http_client, sitemap_index_xml, simple_sitemap_xml):
        """Test parsing sitemap index."""
        # First call returns index, subsequent calls return child sitemaps
        mock_http_client.get.side_effect = [
            MockResponse(content=sitemap_index_xml),
            MockResponse(content=simple_sitemap_xml),
            MockResponse(content=simple_sitemap_xml),
        ]
        
        parser = SitemapParser(mock_http_client, allowed_domains=['utc.edu.vn'])
        urls = parser.parse_sitemap('https://utc.edu.vn/sitemap-index.xml')
        
        # Should get URLs from both child sitemaps
        # Note: Parser deduplicates URLs across child sitemaps, so we get 2 unique URLs
        # (not 4, since both child sitemaps have the same 2 URLs)
        assert len(urls) == 2
        
        # Verify both child sitemaps were fetched
        assert mock_http_client.get.call_count == 3  # Index + 2 children
    
    def test_parse_sitemap_avoids_duplicates(self, mock_http_client):
        """Test sitemap parser avoids processing same sitemap twice."""
        sitemap_xml = b'''<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
    <url><loc>https://utc.edu.vn/page1</loc></url>
</urlset>'''
        
        mock_http_client.get.return_value = MockResponse(content=sitemap_xml)
        
        parser = SitemapParser(mock_http_client, allowed_domains=['utc.edu.vn'])
        
        # Parse same sitemap twice
        urls1 = parser.parse_sitemap('https://utc.edu.vn/sitemap.xml')
        urls2 = parser.parse_sitemap('https://utc.edu.vn/sitemap.xml')
        
        # Second call should return empty (already processed)
        assert len(urls1) == 1
        assert len(urls2) == 0
        
        # HTTP client should only be called once
        assert mock_http_client.get.call_count == 1
    
    def test_parse_sitemap_deduplicates_urls(self, mock_http_client):
        """Test sitemap parser deduplicates URLs within same sitemap."""
        sitemap_xml = b'''<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
    <url><loc>https://utc.edu.vn/page1</loc></url>
    <url><loc>https://utc.edu.vn/page1</loc></url>
    <url><loc>https://utc.edu.vn/page2</loc></url>
</urlset>'''
        
        mock_http_client.get.return_value = MockResponse(content=sitemap_xml)
        
        parser = SitemapParser(mock_http_client, allowed_domains=['utc.edu.vn'])
        urls = parser.parse_sitemap('https://utc.edu.vn/sitemap.xml')
        
        # Should only get 2 URLs (page1 deduplicated)
        assert len(urls) == 2
        assert urls[0].loc == 'https://utc.edu.vn/page1'
        assert urls[1].loc == 'https://utc.edu.vn/page2'
    
    def test_parse_sitemap_respects_max_urls(self, mock_http_client):
        """Test sitemap parser respects max_urls limit."""
        # Create sitemap with 10 URLs
        urls_xml = ''.join([
            f'<url><loc>https://utc.edu.vn/page{i}</loc></url>'
            for i in range(10)
        ])
        sitemap_xml = f'''<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
    {urls_xml}
</urlset>'''.encode('utf-8')
        
        mock_http_client.get.return_value = MockResponse(content=sitemap_xml)
        
        parser = SitemapParser(mock_http_client, allowed_domains=['utc.edu.vn'], max_urls=5)
        urls = parser.parse_sitemap('https://utc.edu.vn/sitemap.xml')
        
        # Should stop at max_urls
        assert len(urls) == 5
    
    def test_parse_sitemap_filters_by_domain(self, mock_http_client):
        """Test sitemap parser filters URLs by allowed domains."""
        sitemap_xml = b'''<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
    <url><loc>https://utc.edu.vn/page1</loc></url>
    <url><loc>https://other.com/page2</loc></url>
    <url><loc>https://utc.edu.vn/page3</loc></url>
</urlset>'''
        
        mock_http_client.get.return_value = MockResponse(content=sitemap_xml)
        
        parser = SitemapParser(
            mock_http_client,
            allowed_domains=['utc.edu.vn']  # Only allow utc.edu.vn
        )
        urls = parser.parse_sitemap('https://utc.edu.vn/sitemap.xml')
        
        # Should only get utc.edu.vn URLs
        assert len(urls) == 2
        assert all('utc.edu.vn' in url.loc for url in urls)
    
    def test_parse_sitemap_filters_by_path_patterns(self, mock_http_client):
        """Test sitemap parser filters URLs by path patterns."""
        sitemap_xml = b'''<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
    <url><loc>https://utc.edu.vn/tuyen-sinh/page1</loc></url>
    <url><loc>https://utc.edu.vn/admin/page2</loc></url>
    <url><loc>https://utc.edu.vn/dao-tao/page3</loc></url>
</urlset>'''
        
        mock_http_client.get.return_value = MockResponse(content=sitemap_xml)
        
        parser = SitemapParser(
            mock_http_client,
            allowed_domains=['utc.edu.vn'],
            allowed_path_patterns=['/tuyen-sinh/.*', '/dao-tao/.*'],
            excluded_path_patterns=['/admin/.*']
        )
        urls = parser.parse_sitemap('https://utc.edu.vn/sitemap.xml')
        
        # Should exclude /admin/ and only include allowed patterns
        assert len(urls) == 2
        assert not any('/admin/' in url.loc for url in urls)
    
    def test_parse_sitemap_http_error(self, mock_http_client):
        """Test sitemap parser handles HTTP errors."""
        mock_http_client.get.side_effect = HTTPError(404, "404 Not Found", "https://utc.edu.vn/nonexistent.xml")
        
        parser = SitemapParser(mock_http_client)
        
        with pytest.raises(HTTPError):
            parser.parse_sitemap('https://utc.edu.vn/nonexistent.xml')
    
    def test_parse_sitemap_invalid_xml(self, mock_http_client):
        """Test sitemap parser handles invalid XML."""
        mock_http_client.get.return_value = MockResponse(content=b'<invalid xml')
        
        parser = SitemapParser(mock_http_client)
        
        with pytest.raises(ValidationError, match="Invalid XML"):
            parser.parse_sitemap('https://utc.edu.vn/sitemap.xml')
    
    def test_parse_datetime_formats(self, mock_http_client):
        """Test parsing various datetime formats."""
        sitemap_xml = b'''<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
    <url>
        <loc>https://utc.edu.vn/page1</loc>
        <lastmod>2024-01-15</lastmod>
    </url>
    <url>
        <loc>https://utc.edu.vn/page2</loc>
        <lastmod>2024-01-15T10:30:00Z</lastmod>
    </url>
    <url>
        <loc>https://utc.edu.vn/page3</loc>
        <lastmod>2024-01-15T10:30:00+07:00</lastmod>
    </url>
</urlset>'''
        
        mock_http_client.get.return_value = MockResponse(content=sitemap_xml)
        
        parser = SitemapParser(mock_http_client, allowed_domains=['utc.edu.vn'])
        urls = parser.parse_sitemap('https://utc.edu.vn/sitemap.xml')
        
        # All three formats should parse successfully
        assert len(urls) == 3
        assert all(url.lastmod is not None for url in urls)
    
    def test_reset(self, mock_http_client, simple_sitemap_xml):
        """Test resetting parser state."""
        mock_http_client.get.return_value = MockResponse(content=simple_sitemap_xml)
        
        parser = SitemapParser(mock_http_client, allowed_domains=['utc.edu.vn'])
        
        # Parse sitemap
        urls1 = parser.parse_sitemap('https://utc.edu.vn/sitemap.xml')
        assert len(urls1) == 2
        
        # Reset and parse again
        parser.reset()
        mock_http_client.get.return_value = MockResponse(content=simple_sitemap_xml)
        urls2 = parser.parse_sitemap('https://utc.edu.vn/sitemap.xml')
        
        # After reset, should get URLs again
        assert len(urls2) == 2
