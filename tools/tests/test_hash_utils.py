"""Tests for hash utilities."""

import pytest
from src.hash_utils import compute_content_hash, verify_hash


class TestHashUtils:
    """Test content hashing."""

    def test_compute_hash_consistent(self):
        """Test hash is consistent for same content."""
        text = "This is test content"

        hash1 = compute_content_hash(text)
        hash2 = compute_content_hash(text)

        assert hash1 == hash2

    def test_compute_hash_different_content(self):
        """Test different content produces different hashes."""
        text1 = "Content A"
        text2 = "Content B"

        hash1 = compute_content_hash(text1)
        hash2 = compute_content_hash(text2)

        assert hash1 != hash2

    def test_compute_hash_format(self):
        """Test hash format is hexadecimal."""
        text = "Test content"
        hash_value = compute_content_hash(text)

        # SHA-256 produces 64 hex characters
        assert len(hash_value) == 64
        assert all(c in "0123456789abcdef" for c in hash_value)

    def test_compute_hash_utf8(self):
        """Test hashing UTF-8 content."""
        text = "Đại học Giao thông Vận tải"
        hash_value = compute_content_hash(text)

        # Should produce valid hash
        assert len(hash_value) == 64

    def test_compute_hash_empty(self):
        """Test hashing empty string."""
        hash_value = compute_content_hash("")

        # Empty string should produce specific hash
        assert len(hash_value) == 64

    def test_verify_hash_valid(self):
        """Test hash verification with valid hash."""
        text = "Test content"
        hash_value = compute_content_hash(text)

        assert verify_hash(text, hash_value)

    def test_verify_hash_invalid(self):
        """Test hash verification with invalid hash."""
        text = "Test content"
        wrong_hash = "0" * 64

        assert not verify_hash(text, wrong_hash)

    def test_whitespace_affects_hash(self):
        """Test that whitespace changes affect hash (as expected)."""
        text1 = "content"
        text2 = "content "  # trailing space

        hash1 = compute_content_hash(text1)
        hash2 = compute_content_hash(text2)

        # Hashes should be different (whitespace matters)
        assert hash1 != hash2
