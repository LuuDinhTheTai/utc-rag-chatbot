"""Tests for Ollama embedding client (/api/embed batch endpoint)."""

import math
from unittest.mock import MagicMock, patch

import pytest
import requests

from src.config import OllamaConfig
from src.embedder import OllamaEmbedder
from src.exceptions import EmbeddingError, LeaseExpiredError
from src.job_context import JobContext


@pytest.fixture
def ollama_config():
    return OllamaConfig(
        base_url="http://localhost:11434",
        slm_model="qwen3:0.6b",
        embedding_model="embeddinggemma:300m-qat-q4_0",
        embedding_dimension=768,
        connect_timeout_seconds=5,
        read_timeout_seconds=120,
        embed_batch_size=2,
        max_retries=3,
    )


def _ok_resp(embeddings):
    resp = MagicMock()
    resp.status_code = 200
    resp.json.return_value = {"embeddings": embeddings}
    return resp


def test_embedder_init(ollama_config):
    embedder = OllamaEmbedder(ollama_config)
    assert embedder.embed_url == "http://localhost:11434/api/embed"


def test_embed_empty_and_oversized_text(ollama_config):
    embedder = OllamaEmbedder(ollama_config)
    with pytest.raises(EmbeddingError, match="Cannot embed empty text"):
        embedder.embed("")
    with pytest.raises(EmbeddingError, match="Cannot embed empty text"):
        embedder.embed("   ")
    with pytest.raises(EmbeddingError, match="max_input_chars"):
        embedder.embed("x" * (ollama_config.max_input_chars + 1))


@patch("src.embedder.requests.Session")
def test_embed_success(mock_session_cls, ollama_config):
    vec = [0.1] * 768
    mock_session = MagicMock()
    mock_session.post.return_value = _ok_resp([vec])
    mock_session_cls.return_value = mock_session

    embedder = OllamaEmbedder(ollama_config)
    result = embedder.embed("test text")

    assert result == vec
    assert len(result) == 768
    _, kwargs = mock_session.post.call_args
    assert kwargs["json"]["input"] == ["test text"]
    assert kwargs["json"]["truncate"] is False


@patch("src.embedder.requests.Session")
def test_embed_rejects_booleans_nan_inf_and_wrong_dim(mock_session_cls, ollama_config):
    mock_session = MagicMock()
    mock_session_cls.return_value = mock_session
    embedder = OllamaEmbedder(ollama_config)

    # Booleans (isinstance(True, int) is True in Python!) must be rejected
    mock_session.post.return_value = _ok_resp([[True] * 768])
    with pytest.raises(EmbeddingError, match="invalid value"):
        embedder.embed("test")

    # NaN
    mock_session.post.return_value = _ok_resp([[float("nan")] * 768])
    with pytest.raises(EmbeddingError, match="invalid value"):
        embedder.embed("test")

    # Inf
    mock_session.post.return_value = _ok_resp([[float("inf")] * 768])
    with pytest.raises(EmbeddingError, match="invalid value"):
        embedder.embed("test")

    # Wrong dimension
    mock_session.post.return_value = _ok_resp([[0.1] * 384])
    with pytest.raises(EmbeddingError, match="dimension mismatch"):
        embedder.embed("test")


@patch("src.embedder.requests.Session")
@patch("src.embedder.time.sleep")
def test_embed_retries_on_429_5xx_and_timeout(mock_sleep, mock_session_cls, ollama_config):
    mock_session = MagicMock()
    mock_session.post.side_effect = [
        MagicMock(status_code=429, text="rate limited"),
        MagicMock(status_code=503, text="unavailable"),
        _ok_resp([[0.2] * 768]),
    ]
    mock_session_cls.return_value = mock_session

    embedder = OllamaEmbedder(ollama_config)
    result = embedder.embed("test text", retry_count=3)
    assert len(result) == 768
    assert mock_session.post.call_count == 3
    assert mock_sleep.call_count == 2


@patch("src.embedder.requests.Session")
def test_embed_does_not_retry_on_400(mock_session_cls, ollama_config):
    mock_session = MagicMock()
    mock_session.post.return_value = MagicMock(status_code=400, text="bad request")
    mock_session_cls.return_value = mock_session

    embedder = OllamaEmbedder(ollama_config)
    with pytest.raises(EmbeddingError, match="status 400"):
        embedder.embed("test text", retry_count=3)
    assert mock_session.post.call_count == 1


@patch("src.embedder.requests.Session")
def test_embed_batch_real_batching_and_job_context(mock_session_cls, ollama_config):
    mock_session = MagicMock()
    mock_session.post.side_effect = [
        _ok_resp([[0.1] * 768, [0.2] * 768]),
        _ok_resp([[0.3] * 768]),
    ]
    mock_session_cls.return_value = mock_session

    embedder = OllamaEmbedder(ollama_config)
    results = embedder.embed_batch(["a", "b", "c"])
    assert len(results) == 3
    assert mock_session.post.call_count == 2  # batch_size=2 -> 2 requests for 3 texts

    # JobContext lost ownership aborts before sending
    import uuid
    ctx = JobContext(job_id=1, claim_token=uuid.uuid4())
    ctx.mark_lost(LeaseExpiredError("expired"))
    with pytest.raises(LeaseExpiredError):
        embedder.embed_batch(["a"], job_context=ctx)


@patch("src.embedder.requests.Session")
def test_health_check_and_close(mock_session_cls, ollama_config):
    mock_session = MagicMock()
    mock_session.post.return_value = _ok_resp([[0.1] * 768])
    mock_session_cls.return_value = mock_session

    embedder = OllamaEmbedder(ollama_config)
    assert embedder.health_check() is True

    mock_session.post.side_effect = requests.exceptions.ConnectionError("down")
    assert embedder.health_check() is False

    embedder.close()
    mock_session.close.assert_called_once()
