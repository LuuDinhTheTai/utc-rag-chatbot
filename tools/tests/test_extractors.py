"""Tests for HTML, PDF, DOCX extractors and Chunker."""

import zipfile
from io import BytesIO
from unittest.mock import MagicMock, patch

import docx
import pytest

from src.chunker import Chunker
from src.exceptions import (
    ContentTooLargeError,
    EmptyContentError,
    ParsingError,
    UnsupportedContentTypeError,
)
from src.extractors import DOCXExtractor, HTMLExtractor, PDFExtractor
from src.extractors.docx import OLE2_MAGIC


class TestHTMLExtractor:
    def test_extract_basic_and_nested_title(self):
        html = b"""
        <html>
        <head><title><span>UTC</span> <b>Admissions</b> 2026</title></head>
        <body>
            <h1>Header</h1>
            <p>First paragraph.</p>
            <script>alert('hidden');</script>
            <style>body { color: red; }</style>
            <p>Second paragraph.</p>
        </body>
        </html>
        """
        result = HTMLExtractor().extract(html, "http://test.com")
        assert result["title"] == "UTC Admissions 2026"
        assert "First paragraph." in result["text"]
        assert "Second paragraph." in result["text"]
        assert "alert" not in result["text"]
        assert "color: red" not in result["text"]

    def test_extract_encoding_utf8_and_cp1252_meta(self):
        utf8_html = '<html><head><title>Tiếng Việt</title></head><body><p>Đại học GTVT</p></body></html>'.encode("utf-8")
        res = HTMLExtractor().extract(utf8_html, "http://test.com")
        assert res["title"] == "Tiếng Việt"
        assert "Đại học GTVT" in res["text"]

        # Latin-1 / Windows-1252 declared charset
        latin_html = b'<html><head><meta charset="iso-8859-1"><title>Caf\xe9</title></head><body><p>R\xe9sum\xe9</p></body></html>'
        res2 = HTMLExtractor().extract(latin_html, "http://test.com")
        assert res2["title"] == "Café"
        assert "Résumé" in res2["text"]

    def test_extract_metadata_single_pass(self):
        html = b"""
        <html><head>
            <title>Test Page</title>
            <meta name="description" content="Test description">
            <meta name="keywords" content="test, keywords">
            <meta name="author" content="Test Author">
        </head><body><p>Content</p></body></html>
        """
        result = HTMLExtractor().extract(html, "http://test.com")
        assert result["metadata"]["description"] == "Test description"
        assert result["metadata"]["keywords"] == "test, keywords"
        assert result["metadata"]["author"] == "Test Author"


class TestPDFExtractor:
    @patch("src.extractors.pdf.pypdf.PdfReader")
    def test_multipage_with_page_markers(self, mock_reader_cls):
        p1, p2 = MagicMock(), MagicMock()
        p1.extract_text.return_value = "First page text."
        p2.extract_text.return_value = "Second page text."
        reader = MagicMock(is_encrypted=False, pages=[p1, p2], metadata=MagicMock(title="Handbook", author="UTC", subject=None, creator=None))
        mock_reader_cls.return_value = reader

        res = PDFExtractor(max_pages=10).extract(b"%PDF-1.4 fake", "https://utc.edu.vn/a.pdf")
        assert res["title"] == "Handbook"
        assert "[Page 1]" in res["text"]
        assert "[Page 2]" in res["text"]
        assert res["metadata"]["extracted_pages"] == 2
        assert res["metadata"]["partial_extraction"] is False

    @patch("src.extractors.pdf.pypdf.PdfReader")
    def test_partial_page_failure_tracked_and_majority_failure_rejected(self, mock_reader_cls):
        p1, p2, p3 = MagicMock(), MagicMock(), MagicMock()
        p1.extract_text.return_value = "Page 1 ok."
        p2.extract_text.side_effect = RuntimeError("corrupt stream")
        p3.extract_text.return_value = "Page 3 ok."
        mock_reader_cls.return_value = MagicMock(is_encrypted=False, pages=[p1, p2, p3], metadata=None)

        res = PDFExtractor().extract(b"%PDF-1.4", "https://utc.edu.vn/partial.pdf")
        assert res["metadata"]["failed_pages"] == [2]
        assert res["metadata"]["partial_extraction"] is True

        # >50% failed -> ParsingError
        p1.extract_text.side_effect = RuntimeError("bad")
        with pytest.raises(ParsingError, match="too incomplete"):
            PDFExtractor().extract(b"%PDF-1.4", "https://utc.edu.vn/bad.pdf")

    @patch("src.extractors.pdf.pypdf.PdfReader")
    def test_encrypted_and_empty_pdf(self, mock_reader_cls):
        enc_reader = MagicMock(is_encrypted=True)
        enc_reader.decrypt.return_value = 0
        mock_reader_cls.return_value = enc_reader
        with pytest.raises(ParsingError, match="encrypted"):
            PDFExtractor().extract(b"%PDF-1.4", "https://utc.edu.vn/enc.pdf")

        # Image-only / no extractable text -> EmptyContentError
        blank = MagicMock()
        blank.extract_text.return_value = "   "
        mock_reader_cls.return_value = MagicMock(is_encrypted=False, pages=[blank], metadata=None)
        with pytest.raises(EmptyContentError, match="No extractable text"):
            PDFExtractor().extract(b"%PDF-1.4", "https://utc.edu.vn/scan.pdf")


class TestDOCXExtractor:
    def _build_docx_bytes(self) -> bytes:
        d = docx.Document()
        d.core_properties.title = "Admissions Guide"
        d.add_heading("Section 1", level=1)
        d.add_paragraph("Intro before table.")

        tbl = d.add_table(rows=2, cols=2)
        tbl.cell(0, 0).text = "MergedHeader"
        tbl.cell(0, 0).merge(tbl.cell(0, 1))
        tbl.cell(1, 0).text = "Row1Col1"
        # Nested table inside cell(1, 1)
        nested = tbl.cell(1, 1).add_table(rows=1, cols=2)
        nested.cell(0, 0).text = "NestedA"
        nested.cell(0, 1).text = "NestedB"

        d.add_paragraph("Outro after table.")
        buf = BytesIO()
        d.save(buf)
        return buf.getvalue()

    def test_preserves_order_merged_cells_nested_tables_and_headings(self):
        raw = self._build_docx_bytes()
        res = DOCXExtractor().extract(raw, "https://utc.edu.vn/guide.docx")

        text = res["text"]
        assert res["title"] == "Admissions Guide"
        assert "# Section 1" in text
        # Order: Intro -> Table -> Outro
        assert text.index("Intro before table.") < text.index("MergedHeader") < text.index("Outro after table.")
        # Merged cell appears only ONCE (not duplicated as "MergedHeader | MergedHeader")
        assert text.count("MergedHeader") == 1
        # Nested table content preserved
        assert "NestedA | NestedB" in text

    def test_legacy_doc_rejected_explicitly(self):
        fake_doc = OLE2_MAGIC + b"\x00" * 128
        with pytest.raises(UnsupportedContentTypeError, match="Legacy .doc"):
            DOCXExtractor().extract(fake_doc, "https://utc.edu.vn/old.doc")

    def test_zip_bomb_limit_enforced(self):
        buf = BytesIO()
        with zipfile.ZipFile(buf, "w", compression=zipfile.ZIP_DEFLATED) as zf:
            zf.writestr("word/document.xml", "A" * 100_000)
        with pytest.raises(ParsingError, match="uncompressed size exceeds limit"):
            DOCXExtractor(max_uncompressed_bytes=10_000).extract(buf.getvalue(), "https://utc.edu.vn/bomb.docx")


class TestChunker:
    def test_validates_parameters(self):
        with pytest.raises(ValueError):
            Chunker(min_chunk_size=200, target_chunk_size=100, max_chunk_size=300)
        with pytest.raises(ValueError):
            Chunker(min_chunk_size=50, target_chunk_size=100, max_chunk_size=200, overlap_size=100)

    def test_preserves_punctuation_overlap_headings_and_hard_max(self):
        chunker = Chunker(min_chunk_size=40, target_chunk_size=100, max_chunk_size=150, overlap_size=25)
        text = "# Admissions 2026\n\n" + " ".join(f"Sentence number {i} explains UTC rules." for i in range(12))
        chunks = chunker.chunk_text(text)

        assert len(chunks) > 1
        for i, c in enumerate(chunks):
            assert c["position"] == i
            assert c["body"] != ""
            assert c["char_count"] == len(c["body"])
            assert len(c["body"]) <= chunker.max_chunk_size
        # Punctuation preserved
        assert "." in chunks[0]["body"]
        # Heading context preserved on subsequent chunks
        assert chunks[1]["body"].startswith("# Admissions 2026")

    def test_splits_word_longer_than_max_size(self):
        chunker = Chunker(min_chunk_size=10, target_chunk_size=30, max_chunk_size=50, overlap_size=0)
        chunks = chunker.chunk_text("X" * 130)
        assert len(chunks) == 3
        assert all(len(c["body"]) <= 50 for c in chunks)
        assert "".join(c["body"] for c in chunks) == "X" * 130

    def test_exceeding_max_input_chars_raises_content_too_large(self):
        chunker = Chunker(min_chunk_size=10, target_chunk_size=50, max_chunk_size=100, overlap_size=0, max_input_chars=200)
        with pytest.raises(ContentTooLargeError, match="exceeds max_input_chars"):
            chunker.chunk_text("A" * 201)
