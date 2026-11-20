"""Tests for Ollama embedding client."""

import pytest
from unittest.mock import Mock, patch, MagicMock
from src.embedder import OllamaEmbedder
from src.config import OllamaConfig
from src.exceptions import EmbeddingError


@pytest.fixture
def ollama_config():
    """Create test Ollama configuration."""
    return OllamaConfig(
        base_url="http://localhost:11434",
        slm_model="qwen3:0.6b",
        embedding_model="embeddinggemma:300m-qat-q4_0",
        embedding_dimension=768,
        connect_timeout_seconds=5,
        read_timeout_seconds=120,
    )


def test_embedder_init(ollama_config):
    """Test embedder initialization."""
    embedder = OllamaEmbedder(ollama_config)
    assert embedder.config == ollama_config
    assert embedder.embed_url == "http://localhost:11434/api/embeddings"
    assert embedder.session is not None


def test_embed_empty_text(ollama_config):
    """Test embedding empty text raises error."""
    embedder = OllamaEmbedder(ollama_config)
    
    with pytest.raises(EmbeddingError, match="Cannot embed empty text"):
        embedder.embed("")
    
    with pytest.raises(EmbeddingError, match="Cannot embed empty text"):
        embedder.embed("   ")


@patch("src.embedder.requests.Session")
def test_embed_success(mock_session_class, ollama_config):
    """Test successful embedding generation."""
    # Create mock embedding vector of correct dimension
    mock_embedding = [0.1] * 768
    
    mock_session = MagicMock()
    mock_response = MagicMock()
    mock_response.status_code = 200
    mock_response.json.return_value = {"embedding": mock_embedding}
    mock_session.post.return_value = mock_response
    mock_session_class.return_value = mock_session
    
    embedder = OllamaEmbedder(ollama_config)
    result = embedder.embed("test text")
    
    assert result == mock_embedding
    assert len(result) == 768
    mock_session.post.assert_called_once()


@patch("src.embedder.requests.Session")
def test_embed_http_error(mock_session_class, ollama_config):
    """Test embedding with HTTP error response."""
    mock_session = MagicMock()
    mock_response = MagicMock()
    mock_response.status_code = 500
    mock_response.text = "Internal server error"
    mock_session.post.return_value = mock_response
    mock_session_class.return_value = mock_session
    
    embedder = OllamaEmbedder(ollama_config)
    
    with pytest.raises(EmbeddingError, match="Ollama API returned status 500"):
        embedder.embed("test text")


@patch("src.embedder.requests.Session")
def test_embed_missing_embedding_field(mock_session_class, ollama_config):
    """Test embedding with missing embedding field."""
    mock_session = MagicMock()
    mock_response = MagicMock()
    mock_response.status_code = 200
    mock_response.json.return_value = {}  # Missing 'embedding'
    mock_session.post.return_value = mock_response
    mock_session_class.return_value = mock_session
    
    embedder = OllamaEmbedder(ollama_config)
    
    with pytest.raises(EmbeddingError, match="missing 'embedding' field"):
        embedder.embed("test text")


@patch("src.embedder.requests.Session")
def test_embed_wrong_dimension(mock_session_class, ollama_config):
    """Test embedding with wrong dimension."""
    # Wrong dimension (384 instead of 768)
    mock_embedding = [0.1] * 384
    
    mock_session = MagicMock()
    mock_response = MagicMock()
    mock_response.status_code = 200
    mock_response.json.return_value = {"embedding": mock_embedding}
    mock_session.post.return_value = mock_response
    mock_session_class.return_value = mock_session
    
    embedder = OllamaEmbedder(ollama_config)
    
    with pytest.raises(
        EmbeddingError, match="Embedding dimension mismatch: expected 768, got 384"
    ):
        embedder.embed("test text")


@patch("src.embedder.requests.Session")
def test_embed_invalid_type(mock_session_class, ollama_config):
    """Test embedding with invalid type."""
    mock_session = MagicMock()
    mock_response = MagicMock()
    mock_response.status_code = 200
    mock_response.json.return_value = {"embedding": "not a list"}
    mock_session.post.return_value = mock_response
    mock_session_class.return_value = mock_session
    
    embedder = OllamaEmbedder(ollama_config)
    
    with pytest.raises(EmbeddingError, match="Invalid embedding type"):
        embedder.embed("test text")


@patch("src.embedder.requests.Session")
def test_embed_infinite_values(mock_session_class, ollama_config):
    """Test embedding with infinite values."""
    mock_embedding = [float('inf')] * 768
    
    mock_session = MagicMock()
    mock_response = MagicMock()
    mock_response.status_code = 200
    mock_response.json.return_value = {"embedding": mock_embedding}
    mock_session.post.return_value = mock_response
    mock_session_class.return_value = mock_session
    
    embedder = OllamaEmbedder(ollama_config)
    
    with pytest.raises(EmbeddingError, match="invalid values"):
        embedder.embed("test text")


@patch("src.embedder.requests.Session")
@patch("src.embedder.time.sleep")
def test_embed_retry_on_timeout(mock_sleep, mock_session_class, ollama_config):
    """Test embedding retry on timeout."""
    import requests
    
    mock_session = MagicMock()
    # First two attempts timeout, third succeeds
    mock_session.post.side_effect = [
        requests.exceptions.Timeout("Timeout 1"),
        requests.exceptions.Timeout("Timeout 2"),
        MagicMock(
            status_code=200, json=lambda: {"embedding": [0.1] * 768}
        ),
    ]
    mock_session_class.return_value = mock_session
    
    embedder = OllamaEmbedder(ollama_config)
    result = embedder.embed("test text", retry_count=3)
    
    assert len(result) == 768
    assert mock_session.post.call_count == 3
    assert mock_sleep.call_count == 2  # Two retries


@patch("src.embedder.requests.Session")
def test_embed_max_retries_exceeded(mock_session_class, ollama_config):
    """Test embedding failure after max retries."""
    import requests
    
    mock_session = MagicMock()
    mock_session.post.side_effect = requests.exceptions.Timeout("Timeout")
    mock_session_class.return_value = mock_session
    
    embedder = OllamaEmbedder(ollama_config)
    
    with pytest.raises(EmbeddingError, match="timeout after 3 attempts"):
        embedder.embed("test text", retry_count=3)


@patch("src.embedder.requests.Session")
def test_embed_no_retry_on_validation_error(mock_session_class, ollama_config):
    """Test that validation errors don't trigger retry."""
    mock_session = MagicMock()
    mock_response = MagicMock()
    mock_response.status_code = 200
    mock_response.json.return_value = {"embedding": [0.1] * 384}  # Wrong dimension
    mock_session.post.return_value = mock_response
    mock_session_class.return_value = mock_session
    
    embedder = OllamaEmbedder(ollama_config)
    
    with pytest.raises(EmbeddingError, match="dimension mismatch"):
        embedder.embed("test text", retry_count=3)
    
    # Should only call once (no retry on validation error)
    assert mock_session.post.call_count == 1


@patch("src.embedder.requests.Session")
def test_embed_batch(mock_session_class, ollama_config):
    """Test batch embedding."""
    mock_session = MagicMock()
    mock_response = MagicMock()
    mock_response.status_code = 200
    
    # Different embeddings for each text
    mock_session.post.side_effect = [
        MagicMock(
            status_code=200,
            json=lambda i=i: {"embedding": [float(i)] * 768}
        )
        for i in range(3)
    ]
    mock_session_class.return_value = mock_session
    
    embedder = OllamaEmbedder(ollama_config)
    results = embedder.embed_batch(["text1", "text2", "text3"])
    
    assert len(results) == 3
    assert all(len(emb) == 768 for emb in results)
    assert mock_session.post.call_count == 3


@patch("src.embedder.requests.Session")
def test_embed_batch_partial_failure(mock_session_class, ollama_config):
    """Test batch embedding with partial failure."""
    mock_session = MagicMock()
    # First succeeds, second fails
    mock_session.post.side_effect = [
        MagicMock(status_code=200, json=lambda: {"embedding": [0.1] * 768}),
        MagicMock(status_code=500, text="Error"),
    ]
    mock_session_class.return_value = mock_session
    
    embedder = OllamaEmbedder(ollama_config)
    
    with pytest.raises(EmbeddingError, match="Failed to embed text 2/2"):
        embedder.embed_batch(["text1", "text2"])


@patch("src.embedder.requests.Session")
def test_health_check_success(mock_session_class, ollama_config):
    """Test health check with healthy service."""
    mock_session = MagicMock()
    mock_response = MagicMock()
    mock_response.status_code = 200
    mock_response.json.return_value = {"embedding": [0.1] * 768}
    mock_session.post.return_value = mock_response
    mock_session_class.return_value = mock_session
    
    embedder = OllamaEmbedder(ollama_config)
    assert embedder.health_check() is True


@patch("src.embedder.requests.Session")
def test_health_check_failure(mock_session_class, ollama_config):
    """Test health check with unhealthy service."""
    mock_session = MagicMock()
    mock_session.post.side_effect = Exception("Service unavailable")
    mock_session_class.return_value = mock_session
    
    embedder = OllamaEmbedder(ollama_config)
    assert embedder.health_check() is False


def test_close(ollama_config):
    """Test session closing."""
    embedder = OllamaEmbedder(ollama_config)
    mock_session = MagicMock()
    embedder.session = mock_session
    
    embedder.close()
    
    mock_session.close.assert_called_once()
