"""Tests for URL utilities."""

import pytest
from src.url_utils import (
    normalize_url,
    get_domain,
    is_same_domain,
    is_valid_url,
    get_canonical_url,
)


class TestNormalizeURL:
    """Test URL normalization."""

    def test_normalize_basic_url(self):
        """Test basic URL normalization."""
        url = "https://utc.edu.vn/path"
        assert normalize_url(url) == "https://utc.edu.vn/path"

    def test_normalize_removes_fragment(self):
        """Test fragment removal."""
        url = "https://utc.edu.vn/page#section"
        assert normalize_url(url) == "https://utc.edu.vn/page"

    def test_normalize_removes_tracking_params(self):
        """Test tracking parameter removal."""
        url = "https://utc.edu.vn/page?utm_source=facebook&id=123"
        assert normalize_url(url) == "https://utc.edu.vn/page?id=123"

    def test_normalize_sorts_query_params(self):
        """Test query parameter sorting."""
        url = "https://utc.edu.vn/page?z=3&a=1&m=2"
        assert normalize_url(url) == "https://utc.edu.vn/page?a=1&m=2&z=3"

    def test_normalize_removes_default_port(self):
        """Test default port removal."""
        assert normalize_url("http://utc.edu.vn:80/page") == "http://utc.edu.vn/page"
        assert (
            normalize_url("https://utc.edu.vn:443/page") == "https://utc.edu.vn/page"
        )

    def test_normalize_keeps_non_default_port(self):
        """Test non-default port preservation."""
        assert (
            normalize_url("https://utc.edu.vn:8080/page")
            == "https://utc.edu.vn:8080/page"
        )

    def test_normalize_lowercases_domain(self):
        """Test domain lowercasing."""
        url = "https://UTC.EDU.VN/Page"
        assert normalize_url(url) == "https://utc.edu.vn/Page"

    def test_normalize_removes_trailing_slash(self):
        """Test trailing slash removal (except root)."""
        assert normalize_url("https://utc.edu.vn/page/") == "https://utc.edu.vn/page"
        assert normalize_url("https://utc.edu.vn/") == "https://utc.edu.vn/"

    def test_normalize_relative_url(self):
        """Test relative URL resolution."""
        base = "https://utc.edu.vn/dir/page.html"
        rel = "../other.html"
        assert normalize_url(rel, base) == "https://utc.edu.vn/other.html"

    def test_normalize_adds_missing_scheme(self):
        """Test scheme addition."""
        # Without base, should default to https
        assert normalize_url("//utc.edu.vn/page") == "https://utc.edu.vn/page"


class TestGetDomain:
    """Test domain extraction."""

    def test_get_domain_basic(self):
        """Test basic domain extraction."""
        assert get_domain("https://utc.edu.vn/page") == "utc.edu.vn"

    def test_get_domain_with_port(self):
        """Test domain extraction with port."""
        assert get_domain("https://utc.edu.vn:8080/page") == "utc.edu.vn:8080"

    def test_get_domain_lowercases(self):
        """Test domain lowercasing."""
        assert get_domain("https://UTC.EDU.VN/page") == "utc.edu.vn"


class TestIsSameDomain:
    """Test same domain checking."""

    def test_same_domain(self):
        """Test same domain detection."""
        url1 = "https://utc.edu.vn/page1"
        url2 = "https://utc.edu.vn/page2"
        assert is_same_domain(url1, url2)

    def test_different_domain(self):
        """Test different domain detection."""
        url1 = "https://utc.edu.vn/page"
        url2 = "https://utc2.edu.vn/page"
        assert not is_same_domain(url1, url2)

    def test_subdomain_different(self):
        """Test subdomain as different domain."""
        url1 = "https://utc.edu.vn/page"
        url2 = "https://www.utc.edu.vn/page"
        assert not is_same_domain(url1, url2)


class TestIsValidURL:
    """Test URL validation."""

    def test_valid_http_url(self):
        """Test valid HTTP URL."""
        assert is_valid_url("http://utc.edu.vn/page")

    def test_valid_https_url(self):
        """Test valid HTTPS URL."""
        assert is_valid_url("https://utc.edu.vn/page")

    def test_invalid_scheme(self):
        """Test invalid scheme rejection."""
        assert not is_valid_url("ftp://utc.edu.vn/page")
        assert not is_valid_url("file:///path/to/file")

    def test_missing_netloc(self):
        """Test missing netloc rejection."""
        assert not is_valid_url("https:///path")
        assert not is_valid_url("https://")

    def test_malformed_url(self):
        """Test malformed URL rejection."""
        assert not is_valid_url("not a url")
        assert not is_valid_url("")
