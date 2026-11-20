"""
Tests for URL Discovery module.
"""

import pytest
from src.url_discovery import URLDiscovery


class TestURLDiscovery:
    """Tests for URLDiscovery class."""
    
    def test_init(self):
        """Test URLDiscovery initialization."""
        discovery = URLDiscovery(
            base_url='https://utc.edu.vn',
            allowed_domains=['utc.edu.vn'],
            allowed_path_patterns=['/tuyen-sinh/.*'],
            excluded_path_patterns=['/admin/.*'],
            max_depth=3,
            same_domain_only=True
        )
        
        assert discovery.base_url == 'https://utc.edu.vn'
        assert discovery.base_domain == 'utc.edu.vn'
        assert 'utc.edu.vn' in discovery.allowed_domains
        assert discovery.max_depth == 3
        assert discovery.same_domain_only is True
    
    def test_discover_links_basic(self):
        """Test discovering basic links from HTML."""
        html = '''
        <html>
            <body>
                <a href="https://utc.edu.vn/page1">Page 1</a>
                <a href="https://utc.edu.vn/page2">Page 2</a>
            </body>
        </html>
        '''
        
        discovery = URLDiscovery(
            base_url='https://utc.edu.vn',
            allowed_domains=['utc.edu.vn']
        )
        
        urls = discovery.discover_links(html, 'https://utc.edu.vn/index', current_depth=0)
        
        assert len(urls) == 2
        assert 'https://utc.edu.vn/page1' in urls
        assert 'https://utc.edu.vn/page2' in urls
    
    def test_discover_links_relative_urls(self):
        """Test discovering relative URLs."""
        html = '''
        <html>
            <body>
                <a href="/page1">Page 1</a>
                <a href="../page2">Page 2</a>
                <a href="page3">Page 3</a>
            </body>
        </html>
        '''
        
        discovery = URLDiscovery(
            base_url='https://utc.edu.vn',
            allowed_domains=['utc.edu.vn']
        )
        
        urls = discovery.discover_links(html, 'https://utc.edu.vn/dir/index', current_depth=0)
        
        # All relative URLs should be resolved
        assert len(urls) >= 2
        assert all(url.startswith('https://') for url in urls)
    
    def test_discover_links_skips_invalid(self):
        """Test that invalid links are skipped."""
        html = '''
        <html>
            <body>
                <a href="#anchor">Anchor</a>
                <a href="javascript:void(0)">JS</a>
                <a href="mailto:test@example.com">Email</a>
                <a href="tel:+1234567890">Phone</a>
                <a href="">Empty</a>
                <a href="https://utc.edu.vn/valid">Valid</a>
            </body>
        </html>
        '''
        
        discovery = URLDiscovery(
            base_url='https://utc.edu.vn',
            allowed_domains=['utc.edu.vn']
        )
        
        urls = discovery.discover_links(html, 'https://utc.edu.vn/index', current_depth=0)
        
        # Should only get the valid HTTP link
        assert len(urls) == 1
        assert urls[0] == 'https://utc.edu.vn/valid'
    
    def test_discover_links_respects_max_depth(self):
        """Test that discovery stops at max depth."""
        html = '''
        <html>
            <body>
                <a href="https://utc.edu.vn/page1">Page 1</a>
            </body>
        </html>
        '''
        
        discovery = URLDiscovery(
            base_url='https://utc.edu.vn',
            allowed_domains=['utc.edu.vn'],
            max_depth=2
        )
        
        # At max depth, should return empty
        urls = discovery.discover_links(html, 'https://utc.edu.vn/index', current_depth=2)
        
        assert len(urls) == 0
    
    def test_discover_links_deduplicates(self):
        """Test that discovery deduplicates URLs."""
        html = '''
        <html>
            <body>
                <a href="https://utc.edu.vn/page1">Link 1</a>
                <a href="https://utc.edu.vn/page1">Link 2 (duplicate)</a>
                <a href="https://utc.edu.vn/page2">Link 3</a>
            </body>
        </html>
        '''
        
        discovery = URLDiscovery(
            base_url='https://utc.edu.vn',
            allowed_domains=['utc.edu.vn']
        )
        
        urls = discovery.discover_links(html, 'https://utc.edu.vn/index', current_depth=0)
        
        # Should only have 2 unique URLs
        assert len(urls) == 2
    
    def test_discover_links_same_domain_only(self):
        """Test that same_domain_only filter works."""
        html = '''
        <html>
            <body>
                <a href="https://utc.edu.vn/page1">Internal</a>
                <a href="https://external.com/page2">External</a>
            </body>
        </html>
        '''
        
        discovery = URLDiscovery(
            base_url='https://utc.edu.vn',
            same_domain_only=True
        )
        
        urls = discovery.discover_links(html, 'https://utc.edu.vn/index', current_depth=0)
        
        # Should only get internal link
        assert len(urls) == 1
        assert 'utc.edu.vn' in urls[0]
    
    def test_discover_links_allowed_domains(self):
        """Test allowed_domains filter."""
        html = '''
        <html>
            <body>
                <a href="https://utc.edu.vn/page1">UTC</a>
                <a href="https://utc2.edu.vn/page2">UTC2</a>
                <a href="https://other.com/page3">Other</a>
            </body>
        </html>
        '''
        
        discovery = URLDiscovery(
            base_url='https://utc.edu.vn',
            allowed_domains=['utc.edu.vn', 'utc2.edu.vn'],
            same_domain_only=False
        )
        
        urls = discovery.discover_links(html, 'https://utc.edu.vn/index', current_depth=0)
        
        # Should get both utc.edu.vn and utc2.edu.vn
        assert len(urls) == 2
        assert not any('other.com' in url for url in urls)
    
    def test_discover_links_path_patterns(self):
        """Test allowed and excluded path patterns."""
        html = '''
        <html>
            <body>
                <a href="https://utc.edu.vn/tuyen-sinh/page1">Tuyen sinh</a>
                <a href="https://utc.edu.vn/admin/page2">Admin</a>
                <a href="https://utc.edu.vn/dao-tao/page3">Dao tao</a>
                <a href="https://utc.edu.vn/other/page4">Other</a>
            </body>
        </html>
        '''
        
        discovery = URLDiscovery(
            base_url='https://utc.edu.vn',
            allowed_domains=['utc.edu.vn'],
            allowed_path_patterns=['/tuyen-sinh/.*', '/dao-tao/.*'],
            excluded_path_patterns=['/admin/.*']
        )
        
        urls = discovery.discover_links(html, 'https://utc.edu.vn/index', current_depth=0)
        
        # Should get tuyen-sinh and dao-tao, exclude admin and other
        assert len(urls) == 2
        assert any('/tuyen-sinh/' in url for url in urls)
        assert any('/dao-tao/' in url for url in urls)
        assert not any('/admin/' in url for url in urls)
        assert not any('/other/' in url for url in urls)
    
    def test_reset(self):
        """Test resetting discovery state."""
        html = '<a href="https://utc.edu.vn/page1">Page 1</a>'
        
        discovery = URLDiscovery(
            base_url='https://utc.edu.vn',
            allowed_domains=['utc.edu.vn']
        )
        
        # Discover URLs
        urls1 = discovery.discover_links(html, 'https://utc.edu.vn/index', current_depth=0)
        assert len(urls1) == 1
        
        # Try to discover same URLs again (should deduplicate to 0)
        urls2 = discovery.discover_links(html, 'https://utc.edu.vn/index', current_depth=0)
        assert len(urls2) == 0
        
        # Reset and discover again
        discovery.reset()
        urls3 = discovery.discover_links(html, 'https://utc.edu.vn/index', current_depth=0)
        assert len(urls3) == 1
    
    def test_get_discovered_count(self):
        """Test getting discovered URL count."""
        html = '''
        <html>
            <body>
                <a href="https://utc.edu.vn/page1">Page 1</a>
                <a href="https://utc.edu.vn/page2">Page 2</a>
            </body>
        </html>
        '''
        
        discovery = URLDiscovery(
            base_url='https://utc.edu.vn',
            allowed_domains=['utc.edu.vn']
        )
        
        assert discovery.get_discovered_count() == 0
        
        discovery.discover_links(html, 'https://utc.edu.vn/index', current_depth=0)
        
        assert discovery.get_discovered_count() == 2
