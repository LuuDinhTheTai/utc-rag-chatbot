"""Tests for SecurityValidator — SSRF, DNS resolution, IPv6/IPv4-mapped, userinfo, redirects."""

import socket

import pytest

from src.exceptions import SecurityError
from src.security_validator import SecurityValidator, is_disallowed_ip


def _public_resolver(host, port=None):
    return ["93.184.216.34"]


class TestSecurityValidator:
    def test_valid_public_urls(self):
        v = SecurityValidator(resolver=_public_resolver)
        host, ips = v.validate_and_resolve("https://utc.edu.vn/page")
        assert host == "utc.edu.vn"
        assert ips == ["93.184.216.34"]
        v.validate_url("http://utc.edu.vn/page")

    def test_invalid_schemes(self):
        v = SecurityValidator(allowed_schemes=["http", "https"], resolver=_public_resolver)
        for bad in ("ftp://utc.edu.vn/file", "file:///etc/passwd", "javascript:alert(1)", "data:text/html,hi"):
            with pytest.raises(SecurityError, match="Invalid URL scheme"):
                v.validate_url(bad)

    def test_missing_host_and_userinfo_blocked(self):
        v = SecurityValidator(resolver=_public_resolver)
        with pytest.raises(SecurityError, match="missing host"):
            v.validate_url("http://")
        with pytest.raises(SecurityError, match="Userinfo"):
            v.validate_url("http://localhost@utc.edu.vn/page")
        with pytest.raises(SecurityError, match="Userinfo"):
            v.validate_url("https://user:pass@utc.edu.vn/page")

    def test_localhost_and_private_ips_blocked(self):
        v = SecurityValidator(resolver=_public_resolver)
        blocked = [
            "http://localhost/admin",
            "http://sub.localhost/admin",
            "http://service.internal/admin",
            "http://127.0.0.1/admin",
            "http://[::1]/admin",
            "http://0.0.0.0/admin",
            "http://10.0.0.1/admin",
            "http://172.16.0.1/admin",
            "http://192.168.1.1/admin",
            "http://169.254.169.254/latest/meta-data/",
            "http://[fc00::1]/admin",
            "http://[fe80::1]/admin",
            "http://[::ffff:127.0.0.1]/admin",
            "http://[::ffff:10.0.0.1]/admin",
            "http://2130706433/",       # decimal 127.0.0.1
            "http://0x7f000001/",       # hex 127.0.0.1
            "http://0177.0.0.1/",       # octal 127.0.0.1
            "http://100.64.0.1/",       # CGNAT
        ]
        for url in blocked:
            with pytest.raises(SecurityError):
                v.validate_url(url)

    def test_dns_resolving_to_private_ip_blocked(self):
        def evil_resolver(host, port=None):
            return ["93.184.216.34", "127.0.0.1"]  # mixed public + private A records

        v = SecurityValidator(resolver=evil_resolver)
        with pytest.raises(SecurityError, match="disallowed IP 127.0.0.1"):
            v.validate_url("https://evil.example.com/hook")

    def test_dns_resolving_to_ipv6_link_local_blocked(self):
        v = SecurityValidator(resolver=lambda h, p=None: ["fe80::1"])
        with pytest.raises(SecurityError, match="disallowed IP"):
            v.validate_url("https://evil6.example.com/")

    def test_dns_failure_raises_security_error(self):
        def failing_resolver(host, port=None):
            raise socket.gaierror("NXDOMAIN")

        v = SecurityValidator(resolver=failing_resolver)
        with pytest.raises(SecurityError, match="DNS resolution failed"):
            v.validate_url("https://nonexistent.invalid/")

    def test_private_ip_allowed_when_configured(self):
        v = SecurityValidator(allow_private_ips=True, resolver=lambda h, p=None: ["192.168.1.1"])
        v.validate_url("http://192.168.1.1/page")

    def test_path_traversal_blocked(self):
        v = SecurityValidator(resolver=_public_resolver)
        for bad in (
            "http://utc.edu.vn/../../../etc/passwd",
            "http://utc.edu.vn/page?file=..\\..\\windows\\system32",
            "http://utc.edu.vn/%2e%2e/admin",
        ):
            with pytest.raises(SecurityError, match="Path traversal"):
                v.validate_url(bad)

    def test_redirect_validation(self):
        v = SecurityValidator(resolver=_public_resolver)
        v.validate_redirect("https://utc.edu.vn/page1", "https://cdn.example.com/asset")

        with pytest.raises(SecurityError, match="redirect destination"):
            v.validate_redirect("https://utc.edu.vn/page", "http://169.254.169.254/latest")

    def test_is_disallowed_ip_helper(self):
        assert is_disallowed_ip("127.0.0.1") is True
        assert is_disallowed_ip("10.0.0.1") is True
        assert is_disallowed_ip("::ffff:192.168.1.1") is True
        assert is_disallowed_ip("224.0.0.1") is True  # multicast
        assert is_disallowed_ip("8.8.8.8") is False
        assert is_disallowed_ip("2606:2800:220:1:248:1893:25c8:1946") is False
