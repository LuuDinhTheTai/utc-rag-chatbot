"""Tests for SitemapParser — SSRF validation, nested sitemaps, domain/path filtering, limits."""

from datetime import datetime
from unittest.mock import MagicMock

import pytest

from src.exceptions import HTTPError, SecurityError, ValidationError
from src.sitemap_parser import SitemapParser, SitemapURL


@pytest.fixture
def mock_http_client():
    return MagicMock()


@pytest.fixture
def simple_sitemap_xml():
    return b"""<?xml version="1.0" encoding="UTF-8"?>
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
</urlset>"""


@pytest.fixture
def sitemap_index_xml():
    return b"""<?xml version="1.0" encoding="UTF-8"?>
<sitemapindex xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
    <sitemap><loc>https://utc.edu.vn/sitemap-1.xml</loc></sitemap>
    <sitemap><loc>https://utc.edu.vn/sitemap-2.xml</loc></sitemap>
</sitemapindex>"""


class TestSitemapURL:
    def test_valid_and_invalid(self):
        url = SitemapURL("https://utc.edu.vn/page", datetime(2024, 1, 15), "weekly", 0.8, "https://utc.edu.vn/sitemap.xml")
        assert url.loc == "https://utc.edu.vn/page"

        with pytest.raises(ValidationError):
            SitemapURL("", None, None, None, "https://utc.edu.vn/sitemap.xml")
        with pytest.raises(ValidationError):
            SitemapURL("not-a-url", None, None, None, "https://utc.edu.vn/sitemap.xml")
        with pytest.raises(ValidationError):
            SitemapURL("https://utc.edu.vn/page", None, None, 1.5, "https://utc.edu.vn/sitemap.xml")


class TestSitemapParser:
    def test_parse_simple_sitemap_enables_security_validation(self, mock_http_client, simple_sitemap_xml):
        mock_http_client.fetch.return_value = (simple_sitemap_xml, "application/xml", None)
        parser = SitemapParser(http_client=mock_http_client, allowed_domains=["utc.edu.vn"])

        urls = parser.parse_sitemap("https://utc.edu.vn/sitemap.xml")
        assert len(urls) == 2
        assert urls[0].loc == "https://utc.edu.vn/tuyen-sinh"
        assert urls[0].priority == 0.8
        assert urls[0].lastmod == datetime(2024, 1, 15)
        # Must validate security (no SSRF bypass)
        _, kwargs = mock_http_client.fetch.call_args
        assert kwargs["validate_security"] is True

    def test_parse_sitemap_index_and_dedup(self, mock_http_client, sitemap_index_xml, simple_sitemap_xml):
        child2 = b"""<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
    <url><loc>https://utc.edu.vn/tuyen-sinh</loc></url>
    <url><loc>https://utc.edu.vn/tin-tuc</loc></url>
</urlset>"""
        mock_http_client.fetch.side_effect = [
            (sitemap_index_xml, "application/xml", None),
            (simple_sitemap_xml, "application/xml", None),
            (child2, "application/xml", None),
        ]
        parser = SitemapParser(http_client=mock_http_client, allowed_domains=["utc.edu.vn"])
        urls = parser.parse_sitemap("https://utc.edu.vn/sitemap_index.xml")
        locs = [u.loc for u in urls]
        assert locs == [
            "https://utc.edu.vn/tuyen-sinh",
            "https://utc.edu.vn/dao-tao",
            "https://utc.edu.vn/tin-tuc",
        ]

    def test_filters_domain_and_path_patterns(self, mock_http_client):
        xml = b"""<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
    <url><loc>https://utc.edu.vn/tuyen-sinh/2026</loc></url>
    <url><loc>https://utc.edu.vn/tuyen-sinh/admin</loc></url>
    <url><loc>https://utc.edu.vn.attacker.com/tuyen-sinh/x</loc></url>
    <url><loc>https://utc.edu.vn/other</loc></url>
</urlset>"""
        mock_http_client.fetch.return_value = (xml, "application/xml", None)
        parser = SitemapParser(
            http_client=mock_http_client,
            allowed_domains=["utc.edu.vn"],
            allowed_path_patterns=[r"/tuyen-sinh/.*"],
            excluded_path_patterns=[r"/admin"],
        )
        urls = parser.parse_sitemap("https://utc.edu.vn/sitemap.xml")
        assert [u.loc for u in urls] == ["https://utc.edu.vn/tuyen-sinh/2026"]

    def test_max_urls_and_errors_and_reset(self, mock_http_client, simple_sitemap_xml):
        mock_http_client.fetch.return_value = (simple_sitemap_xml, "application/xml", None)
        parser = SitemapParser(http_client=mock_http_client, max_urls=1)
        assert len(parser.parse_sitemap("https://utc.edu.vn/sitemap.xml")) == 1
        # Calling again without reset skips already-processed sitemap
        assert parser.parse_sitemap("https://utc.edu.vn/sitemap.xml") == []
        parser.reset()
        assert len(parser.parse_sitemap("https://utc.edu.vn/sitemap.xml")) == 1

        # HTTP error propagates
        mock_http_client.fetch.side_effect = HTTPError(404, "Not Found", "https://utc.edu.vn/s2.xml")
        with pytest.raises(HTTPError):
            parser.parse_sitemap("https://utc.edu.vn/s2.xml")

        # SecurityError propagates
        mock_http_client.fetch.side_effect = SecurityError("SSRF")
        with pytest.raises(SecurityError):
            parser.parse_sitemap("https://utc.edu.vn/s3.xml")

        # Invalid XML raises ValidationError
        mock_http_client.fetch.side_effect = None
        mock_http_client.fetch.return_value = (b"<not-xml", "text/html", None)
        with pytest.raises(ValidationError, match="Invalid XML"):
            parser.parse_sitemap("https://utc.edu.vn/s4.xml")

        # Invalid regex rejected at init
        with pytest.raises(ValidationError, match="Invalid regex"):
            SitemapParser(http_client=mock_http_client, allowed_path_patterns=["[bad"])

    def test_root_and_child_sitemap_domain_allowlist_checked_before_fetch(self, mock_http_client, simple_sitemap_xml):
        # Root sitemap outside allowlist -> rejected BEFORE any HTTP fetch
        parser = SitemapParser(http_client=mock_http_client, allowed_domains=["utc.edu.vn"])
        with pytest.raises(ValidationError, match="Sitemap domain not in allowlist"):
            parser.parse_sitemap("https://external-public.com/sitemap.xml")
        mock_http_client.fetch.assert_not_called()

        # Sitemap index with 1 allowed child + 1 disallowed public-domain child
        index_xml = b"""<?xml version="1.0" encoding="UTF-8"?>
<sitemapindex xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
    <sitemap><loc>https://utc.edu.vn/sitemap-ok.xml</loc></sitemap>
    <sitemap><loc>https://other-public-domain.com/sitemap-evil.xml</loc></sitemap>
</sitemapindex>"""
        mock_http_client.fetch.side_effect = [
            (index_xml, "application/xml", None),
            (simple_sitemap_xml, "application/xml", None),
        ]
        urls = parser.parse_sitemap("https://utc.edu.vn/sitemap_index.xml")
        assert len(urls) == 2
        # Only index + allowed child were fetched; disallowed child was NEVER fetched
        fetched_urls = [c[0][0] for c in mock_http_client.fetch.call_args_list]
        assert fetched_urls == [
            "https://utc.edu.vn/sitemap_index.xml",
            "https://utc.edu.vn/sitemap-ok.xml",
        ]
        assert len(parser.failed_child_sitemaps) == 1
        assert "other-public-domain.com" in parser.failed_child_sitemaps[0]
