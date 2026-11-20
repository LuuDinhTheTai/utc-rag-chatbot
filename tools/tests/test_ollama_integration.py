"""Ollama integration tests - requires running Ollama service."""

import os
import pytest

from src.embedder import OllamaEmbedder
from src.config import OllamaConfig

# Skip all tests if OLLAMA_BASE_URL not set
pytestmark = pytest.mark.skipif(
    not os.getenv("OLLAMA_BASE_URL"),
    reason="No OLLAMA_BASE_URL environment variable - Ollama integration tests skipped"
)


@pytest.fixture
def ollama_config():
    """Get Ollama configuration from environment."""
    return OllamaConfig()


@pytest.fixture
def embedder(ollama_config):
    """Create OllamaEmbedder with actual service."""
    return OllamaEmbedder(
        base_url=ollama_config.base_url,
        model=ollama_config.model,
        expected_dimension=ollama_config.embedding_dimension,
    )


def test_ollama_service_available(embedder):
    """Test that Ollama service is reachable."""
    # This will test basic connectivity
    embedding = embedder.embed("test")
    assert embedding is not None
    assert len(embedding) > 0


def test_embedding_dimension_correct(embedder, ollama_config):
    """Test that embedding dimension matches expected (768)."""
    embedding = embedder.embed("Hello world")
    
    assert len(embedding) == ollama_config.embedding_dimension, \
        f"Expected {ollama_config.embedding_dimension} dimensions, got {len(embedding)}"


def test_embedding_consistent(embedder):
    """Test that same text produces same embedding."""
    text = "Consistency test"
    
    embedding1 = embedder.embed(text)
    embedding2 = embedder.embed(text)
    
    # Should be identical for same text
    assert embedding1 == embedding2


def test_embedding_different_for_different_text(embedder):
    """Test that different texts produce different embeddings."""
    embedding1 = embedder.embed("First text")
    embedding2 = embedder.embed("Second text")
    
    # Should be different
    assert embedding1 != embedding2


def test_embedding_batch(embedder):
    """Test batch embedding."""
    texts = ["Text 1", "Text 2", "Text 3"]
    
    embeddings = embedder.embed_batch(texts)
    
    assert len(embeddings) == 3
    for emb in embeddings:
        assert len(emb) == 768


def test_vietnamese_text(embedder):
    """Test embedding Vietnamese text."""
    vietnamese = "Đại học Công nghệ Thông tin và Truyền thông"
    
    embedding = embedder.embed(vietnamese)
    
    assert len(embedding) == 768
    assert all(isinstance(x, float) for x in embedding)


def test_empty_text_handling(embedder):
    """Test handling of empty text."""
    with pytest.raises(Exception):  # Should raise some error
        embedder.embed("")


def test_very_long_text(embedder):
    """Test handling of very long text."""
    long_text = "This is a test. " * 1000  # ~16,000 chars
    
    embedding = embedder.embed(long_text)
    
    # Should still work
    assert len(embedding) == 768


if __name__ == "__main__":
    print("Ollama Integration Tests")
    print("=" * 60)
    print("\nREQUIRED ENVIRONMENT:")
    print("  OLLAMA_BASE_URL")
    print("  OLLAMA_MODEL (optional, defaults to embeddinggemma:300m-qat-q4_0)")
    print("\nRun: pytest tests/test_ollama_integration.py -v")
