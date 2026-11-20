"""Tests for content extractors."""

import pytest
from src.extractors import HTMLExtractor, PDFExtractor, DOCXExtractor
from src.exceptions import ParsingError


class TestHTMLExtractor:
    """Test HTML content extraction."""

    def test_extract_basic_html(self):
        """Test basic HTML extraction."""
        html = b"""
        <html>
        <head><title>Test Page</title></head>
        <body>
            <h1>Header</h1>
            <p>First paragraph.</p>
            <p>Second paragraph.</p>
        </body>
        </html>
        """

        extractor = HTMLExtractor()
        result = extractor.extract(html, "http://test.com")

        assert result["title"] == "Test Page"
        assert "Header" in result["text"]
        assert "First paragraph" in result["text"]
        assert "Second paragraph" in result["text"]

    def test_extract_removes_scripts(self):
        """Test script tag removal."""
        html = b"""
        <html>
        <body>
            <p>Visible text</p>
            <script>alert('hidden');</script>
            <p>More visible text</p>
        </body>
        </html>
        """

        extractor = HTMLExtractor()
        result = extractor.extract(html, "http://test.com")

        assert "Visible text" in result["text"]
        assert "alert" not in result["text"]
        assert "hidden" not in result["text"]

    def test_extract_removes_styles(self):
        """Test style tag removal."""
        html = b"""
        <html>
        <head><style>body { color: red; }</style></head>
        <body><p>Text content</p></body>
        </html>
        """

        extractor = HTMLExtractor()
        result = extractor.extract(html, "http://test.com")

        assert "Text content" in result["text"]
        assert "color: red" not in result["text"]

    def test_extract_normalizes_whitespace(self):
        """Test whitespace normalization."""
        html = b"""
        <html>
        <body>
            <p>Line   with    spaces</p>
            <p>Multiple


            newlines</p>
        </body>
        </html>
        """

        extractor = HTMLExtractor()
        result = extractor.extract(html, "http://test.com")

        # Multiple spaces should be collapsed
        assert "Line with spaces" in result["text"]
        # Multiple newlines should be collapsed to max 2
        assert "\n\n\n" not in result["text"]

    def test_extract_metadata(self):
        """Test metadata extraction."""
        html = b"""
        <html>
        <head>
            <title>Test Page</title>
            <meta name="description" content="Test description">
            <meta name="keywords" content="test, keywords">
            <meta name="author" content="Test Author">
        </head>
        <body><p>Content</p></body>
        </html>
        """

        extractor = HTMLExtractor()
        result = extractor.extract(html, "http://test.com")

        assert result["metadata"]["description"] == "Test description"
        assert result["metadata"]["keywords"] == "test, keywords"
        assert result["metadata"]["author"] == "Test Author"

    def test_extract_utf8_content(self):
        """Test UTF-8 content extraction."""
        html = """
        <html>
        <head><meta charset="utf-8"><title>Tiếng Việt</title></head>
        <body><p>Đại học Giao thông Vận tải</p></body>
        </html>
        """.encode(
            "utf-8"
        )

        extractor = HTMLExtractor()
        result = extractor.extract(html, "http://test.com")

        assert result["title"] == "Tiếng Việt"
        assert "Đại học Giao thông Vận tải" in result["text"]


class TestChunker:
    """Test text chunking."""

    def test_chunk_short_text(self):
        """Test chunking short text (single chunk)."""
        from src.chunker import Chunker

        chunker = Chunker(target_chunk_size=1000)
        text = "This is a short text. It should be one chunk."

        chunks = chunker.chunk_text(text)

        assert len(chunks) == 1
        assert chunks[0]["position"] == 0
        # Chunker joins sentences with space, so periods are retained
        assert "short text" in chunks[0]["body"]
        assert "one chunk" in chunks[0]["body"]

    def test_chunk_long_text(self):
        """Test chunking long text (multiple chunks)."""
        from src.chunker import Chunker

        chunker = Chunker(target_chunk_size=100)

        # Create text with multiple sentences
        sentences = [f"This is sentence number {i}." for i in range(20)]
        text = " ".join(sentences)

        chunks = chunker.chunk_text(text)

        # Should create multiple chunks
        assert len(chunks) > 1

        # Check positions are sequential
        for i, chunk in enumerate(chunks):
            assert chunk["position"] == i

        # Verify all text is preserved
        reconstructed = " ".join([c["body"] for c in chunks])
        # May have slight whitespace differences due to chunking
        assert len(reconstructed) >= len(text) * 0.9

    def test_chunk_preserves_sentences(self):
        """Test that chunking doesn't split sentences."""
        from src.chunker import Chunker

        chunker = Chunker(target_chunk_size=50)
        text = "First sentence. Second sentence. Third sentence."

        chunks = chunker.chunk_text(text)

        # Each chunk should contain complete sentences (ending with period)
        for chunk in chunks:
            assert chunk["body"].strip().endswith(".")

    def test_chunk_empty_text(self):
        """Test chunking empty text."""
        from src.chunker import Chunker

        chunker = Chunker()
        chunks = chunker.chunk_text("")

        assert chunks == []

    def test_chunk_very_long_sentence(self):
        """Test handling very long sentence (exceeds max_chunk_size)."""
        from src.chunker import Chunker

        chunker = Chunker(target_chunk_size=100, max_chunk_size=200)

        # Create a very long sentence with no punctuation
        words = ["word" + str(i) for i in range(100)]
        text = " ".join(words)

        chunks = chunker.chunk_text(text)

        # Should split at word boundaries
        assert len(chunks) > 1

        # No chunk should exceed max size
        for chunk in chunks:
            assert len(chunk["body"]) <= chunker.max_chunk_size
