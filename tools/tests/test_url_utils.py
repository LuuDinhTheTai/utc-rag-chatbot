"""Tests for URL utilities: normalization, domain matching, IDN, IPv6, userinfo."""

import pytest

from src.url_utils import (
    get_canonical_url,
    get_domain,
    get_hostname,
    is_domain_allowed,
    is_same_domain,
    is_valid_url,
    normalize_url,
)


class TestNormalizeURL:
    def test_basic_and_fragment_and_tracking(self):
        assert normalize_url("https://utc.edu.vn/path") == "https://utc.edu.vn/path"
        assert normalize_url("https://utc.edu.vn/page#section") == "https://utc.edu.vn/page"
        assert normalize_url("https://utc.edu.vn/page?utm_source=fb&UTM_MEDIUM=cpc&id=123") == "https://utc.edu.vn/page?id=123"
        assert normalize_url("https://utc.edu.vn/page?z=3&a=1&m=2") == "https://utc.edu.vn/page?a=1&m=2&z=3"

    def test_ports_scheme_slash_relative_and_dots(self):
        assert normalize_url("http://utc.edu.vn:80/page") == "http://utc.edu.vn/page"
        assert normalize_url("https://utc.edu.vn:443/page") == "https://utc.edu.vn/page"
        assert normalize_url("https://utc.edu.vn:8080/page") == "https://utc.edu.vn:8080/page"
        assert normalize_url("https://UTC.EDU.VN/Page") == "https://utc.edu.vn/Page"
        assert normalize_url("https://utc.edu.vn/page/") == "https://utc.edu.vn/page"
        assert normalize_url("https://utc.edu.vn/") == "https://utc.edu.vn/"
        assert normalize_url("../other.html", "https://utc.edu.vn/dir/page.html") == "https://utc.edu.vn/other.html"
        assert normalize_url("https://utc.edu.vn/a/./b/../c") == "https://utc.edu.vn/a/c"
        assert normalize_url("//utc.edu.vn/page") == "https://utc.edu.vn/page"

    def test_strips_userinfo_and_preserves_ipv6(self):
        assert normalize_url("https://user:pass@utc.edu.vn/p") == "https://utc.edu.vn/p"
        assert normalize_url("http://[2001:db8::1]:8080/p") == "http://[2001:db8::1]:8080/p"
        assert get_canonical_url("https://UTC.EDU.VN/a/") == "https://utc.edu.vn/a"

    def test_preserves_duplicate_slashes_and_percent_encoding(self):
        # Repeated slashes must NOT be collapsed (may represent distinct server resources)
        assert normalize_url("https://utc.edu.vn/a//b") == "https://utc.edu.vn/a//b"
        assert normalize_url("https://utc.edu.vn/a//b/") == "https://utc.edu.vn/a//b"
        assert normalize_url("https://utc.edu.vn/a//b/../c") == "https://utc.edu.vn/a//c"
        # Percent-encoding and query parameters preserved stably
        assert normalize_url("https://utc.edu.vn/tuyen%20sinh?b=2&a=1") == "https://utc.edu.vn/tuyen%20sinh?a=1&b=2"


class TestDomainMatching:
    def test_get_domain_strips_port_and_userinfo(self):
        assert get_domain("https://utc.edu.vn/page") == "utc.edu.vn"
        assert get_domain("https://utc.edu.vn:8080/page") == "utc.edu.vn"
        assert get_hostname("https://user:pass@UTC.EDU.VN:8443/page") == "utc.edu.vn"

    def test_is_same_domain(self):
        assert is_same_domain("https://utc.edu.vn/a", "https://utc.edu.vn/b")
        assert is_same_domain("https://utc.edu.vn:8080/a", "https://utc.edu.vn/b")
        assert not is_same_domain("https://utc.edu.vn:8080/a", "https://utc.edu.vn/b", include_port=True)
        assert not is_same_domain("https://utc.edu.vn/a", "https://www.utc.edu.vn/a")

    def test_is_domain_allowed_prevents_suffix_confusion(self):
        assert is_domain_allowed("utc.edu.vn", ["utc.edu.vn"]) is True
        assert is_domain_allowed("tuyensinh.utc.edu.vn", ["utc.edu.vn"]) is True
        assert is_domain_allowed("utc.edu.vn.attacker.com", ["utc.edu.vn"]) is False
        assert is_domain_allowed("notutc.edu.vn", ["utc.edu.vn"]) is False


class TestIsValidURL:
    def test_valid_and_invalid(self):
        assert is_valid_url("http://utc.edu.vn/page")
        assert is_valid_url("https://utc.edu.vn/page")
        assert not is_valid_url("ftp://utc.edu.vn/page")
        assert not is_valid_url("file:///path/to/file")
        assert not is_valid_url("https:///path")
        assert not is_valid_url("https://")
        assert not is_valid_url("not a url")
        assert not is_valid_url("")
