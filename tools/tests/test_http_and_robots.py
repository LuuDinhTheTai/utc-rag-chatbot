"""Tests for HTTPClient (GET/HEAD SSRF, redirects, limits, retries) and RobotsChecker."""

from unittest.mock import MagicMock, patch

import pytest
import requests

from src.config import CrawlerConfig
from src.exceptions import (
    ContentTooLargeError,
    HTTPError,
    RobotsDisallowedError,
    RobotsUnavailableError,
    SecurityError,
    UnsupportedContentTypeError,
)
from src.http_client import HTTPClient
from src.robots import RobotsChecker
from src.security_validator import SecurityValidator


def _make_client(**cfg_overrides):
    cfg = CrawlerConfig(
        default_rate_limit_seconds=0.0,
        max_retries=2,
        max_redirects=2,
        max_content_size_mb=1,
        max_retry_after_seconds=5,
        **cfg_overrides,
    )
    validator = SecurityValidator(resolver=lambda h, p=None: ["93.184.216.34"])
    return HTTPClient(cfg, security_validator=validator)


def _resp(status=200, headers=None, chunks=None, reason="OK"):
    r = MagicMock()
    r.status_code = status
    r.reason = reason
    r.headers = headers or {}
    r.is_redirect = status in (301, 302, 303, 307, 308)
    r.iter_content.return_value = iter(chunks or [b"hello"])
    return r


class TestHTTPClient:
    def test_trust_env_disabled_to_prevent_proxy_ssrf_bypass(self):
        client = _make_client()
        assert client.session.trust_env is False

    def test_head_blocks_ssrf_and_private_redirect(self):
        client = _make_client()
        with pytest.raises(SecurityError):
            client.head("http://127.0.0.1/secret")

        # HEAD redirect to private IP must be blocked
        client.session.request = MagicMock(
            return_value=_resp(302, headers={"Location": "http://169.254.169.254/latest"})
        )
        with pytest.raises(SecurityError, match="redirect destination"):
            client.head("https://utc.edu.vn/start")

    def test_redirect_loop_and_exact_max_redirects(self):
        client = _make_client()  # max_redirects=2

        # Loop A -> B -> A
        client.session.request = MagicMock(side_effect=[
            _resp(302, headers={"Location": "https://utc.edu.vn/b"}),
            _resp(302, headers={"Location": "https://utc.edu.vn/a"}),
        ])
        with pytest.raises(HTTPError, match="Redirect loop"):
            client.fetch("https://utc.edu.vn/a")

        # Exactly 2 redirects succeeds (no off-by-one!)
        client.session.request = MagicMock(side_effect=[
            _resp(302, headers={"Location": "https://utc.edu.vn/r1"}),
            _resp(302, headers={"Location": "https://utc.edu.vn/r2"}),
            _resp(200, headers={"Content-Type": "text/html"}, chunks=[b"<p>ok</p>"]),
        ])
        body, ctype, _ = client.fetch("https://utc.edu.vn/r0")
        assert body == b"<p>ok</p>" and ctype == "text/html"

        # 3rd redirect exceeds max_redirects=2
        client.session.request = MagicMock(side_effect=[
            _resp(302, headers={"Location": "https://utc.edu.vn/r1"}),
            _resp(302, headers={"Location": "https://utc.edu.vn/r2"}),
            _resp(302, headers={"Location": "https://utc.edu.vn/r3"}),
        ])
        with pytest.raises(HTTPError, match="Too many redirects"):
            client.fetch("https://utc.edu.vn/r0")

    def test_invalid_content_length_and_streaming_size_limit(self):
        client = _make_client()  # 1 MB limit

        # Malformed Content-Length does NOT crash with ValueError
        client.session.request = MagicMock(
            return_value=_resp(200, headers={"Content-Type": "text/html", "Content-Length": "not-a-number"}, chunks=[b"ok"])
        )
        body, _, _ = client.fetch("https://utc.edu.vn/page")
        assert body == b"ok"

        # Exceeding limit during streaming raises ContentTooLargeError and closes response
        big_resp = _resp(200, headers={"Content-Type": "text/html"}, chunks=[b"a" * (600 * 1024), b"b" * (600 * 1024)])
        client.session.request = MagicMock(return_value=big_resp)
        with pytest.raises(ContentTooLargeError):
            client.fetch("https://utc.edu.vn/huge")
        big_resp.close.assert_called_once()

    def test_rejects_legacy_doc_content_type(self):
        client = _make_client()
        client.session.request = MagicMock(
            return_value=_resp(200, headers={"Content-Type": "application/msword"}, chunks=[b"doc"])
        )
        with pytest.raises(UnsupportedContentTypeError):
            client.fetch("https://utc.edu.vn/old.doc")

    @patch("src.http_client.time.sleep")
    def test_retries_429_with_capped_retry_after_and_timeout(self, mock_sleep):
        client = _make_client()  # max_retry_after_seconds=5
        client.session.request = MagicMock(side_effect=[
            _resp(429, headers={"Retry-After": "99999"}),
            requests.exceptions.Timeout("slow"),
            _resp(200, headers={"Content-Type": "text/html"}, chunks=[b"ok"]),
        ])
        body, _, _ = client.fetch("https://utc.edu.vn/retry")
        assert body == b"ok"
        # Retry-After was capped at 5s, not 99999s
        assert mock_sleep.call_args_list[0][0][0] == 5.0


class TestRobotsChecker:
    def test_respect_robots_false_disables_all_checks(self):
        http = MagicMock()
        checker = RobotsChecker("UTC-Bot", http_client=http, respect_robots_txt=False)
        assert checker.is_allowed("https://utc.edu.vn/admin") is True
        assert checker.get_crawl_delay("https://utc.edu.vn/admin") is None
        http.fetch.assert_not_called()

    def test_parses_disallow_and_crawl_delay_via_http_client(self):
        http = MagicMock()
        robots_txt = b"User-agent: *\nDisallow: /private/\nCrawl-delay: 5\n"
        http.fetch.return_value = (robots_txt, "text/plain", None)

        checker = RobotsChecker("UTC-Bot", http_client=http, respect_robots_txt=True, max_crawl_delay_seconds=30.0)
        assert checker.is_allowed("https://utc.edu.vn/public") is True
        assert checker.is_allowed("https://utc.edu.vn/private/secret") is False
        assert checker.get_crawl_delay("https://utc.edu.vn/public") == 5.0
        # Cached -> only 1 fetch!
        assert http.fetch.call_count == 1
        _, kwargs = http.fetch.call_args
        assert kwargs["validate_security"] is True

        with pytest.raises(RobotsDisallowedError):
            checker.check_and_raise("https://utc.edu.vn/private/secret")

    def test_robots_fetch_policies_404_403_5xx_and_ssrf(self):
        http = MagicMock()
        checker = RobotsChecker("UTC-Bot", http_client=http, respect_robots_txt=True)

        # 404 -> allow
        http.fetch.side_effect = HTTPError(404, "Not Found", "https://a.example.com/robots.txt")
        assert checker.is_allowed("https://a.example.com/page") is True

        # 403 -> disallow all
        http.fetch.side_effect = HTTPError(403, "Forbidden", "https://b.example.com/robots.txt")
        assert checker.is_allowed("https://b.example.com/page") is False

        # 503 -> fail closed (RobotsUnavailableError)
        http.fetch.side_effect = HTTPError(503, "Unavailable", "https://c.example.com/robots.txt")
        with pytest.raises(RobotsUnavailableError):
            checker.is_allowed("https://c.example.com/page")

        # SSRF -> SecurityError propagates
        http.fetch.side_effect = SecurityError("Private IP")
        with pytest.raises(SecurityError):
            checker.is_allowed("http://127.0.0.1/page")

