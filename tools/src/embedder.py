"""Ollama embedding client using the ``/api/embed`` batch endpoint.

Matches the backend ``OllamaClient`` contract:
- Endpoint: ``POST {base_url}/api/embed`` with ``{"model": ..., "input": [...], "truncate": false}``
- Dimension: 768 (strictly validated; rejects booleans, NaN, Inf)
- Retries transient errors (HTTP 429, 500, 502, 503, 504, timeouts, connection errors)
  with bounded exponential backoff
- Checks ``JobContext.checkpoint()`` between batches and retries so long embedding
  runs cannot outlive a job lease silently.
"""

import logging
import math
import time
from typing import List, Optional

import requests

from .config import OllamaConfig
from .exceptions import EmbeddingError
from .job_context import JobContext

logger = logging.getLogger(__name__)

TRANSIENT_EMBED_STATUSES = frozenset({429, 500, 502, 503, 504})


class OllamaEmbedder:
    """Client for generating embeddings via Ollama ``/api/embed``."""

    def __init__(self, config: OllamaConfig):
        self.config = config
        self.embed_url = f"{config.base_url.rstrip('/')}/api/embed"
        self.session = requests.Session()
        self.session.headers.update({"Content-Type": "application/json"})

    def _validate_vector(self, vec: object) -> List[float]:
        if not isinstance(vec, list):
            raise EmbeddingError(f"Invalid embedding type: {type(vec).__name__}")
        if len(vec) != self.config.embedding_dimension:
            raise EmbeddingError(
                f"Embedding dimension mismatch: expected {self.config.embedding_dimension}, got {len(vec)}"
            )
        result: List[float] = []
        for v in vec:
            # In Python isinstance(True, int) is True — explicitly reject bool!
            if isinstance(v, bool) or not isinstance(v, (int, float)) or not math.isfinite(v):
                raise EmbeddingError(f"Embedding contains invalid value: {v!r}")
            result.append(float(v))
        return result

    def _validate_input_text(self, text: str) -> str:
        if not isinstance(text, str) or not text.strip():
            raise EmbeddingError("Cannot embed empty text")
        if len(text) > self.config.max_input_chars:
            raise EmbeddingError(
                f"Input text exceeds max_input_chars ({len(text)} > {self.config.max_input_chars})"
            )
        return text

    def _sleep(self, seconds: float, job_context: Optional[JobContext]) -> None:
        if job_context:
            job_context.sleep(seconds)
        else:
            time.sleep(seconds)

    def _post_embed(
        self,
        inputs: List[str],
        retry_count: Optional[int] = None,
        job_context: Optional[JobContext] = None,
    ) -> List[List[float]]:
        attempts = max(1, self.config.max_retries if retry_count is None else retry_count)

        for attempt in range(attempts):
            if job_context:
                job_context.checkpoint()
            try:
                response = self.session.post(
                    self.embed_url,
                    json={
                        "model": self.config.embedding_model,
                        "input": inputs,
                        "truncate": False,
                    },
                    timeout=(
                        self.config.connect_timeout_seconds,
                        self.config.read_timeout_seconds,
                    ),
                )

                if response.status_code in TRANSIENT_EMBED_STATUSES:
                    status = response.status_code
                    body_preview = response.text[:200]
                    response.close()
                    if attempt + 1 < attempts:
                        wait_time = min(2 ** attempt, 30)
                        logger.warning(
                            "Ollama transient HTTP %d (attempt %d/%d), retrying in %ss: %s",
                            status, attempt + 1, attempts, wait_time, body_preview,
                        )
                        self._sleep(wait_time, job_context)
                        continue
                    raise EmbeddingError(
                        f"Ollama API returned HTTP {status} after {attempts} attempts: {body_preview}"
                    )

                if response.status_code != 200:
                    body_preview = response.text[:300]
                    response.close()
                    raise EmbeddingError(
                        f"Ollama API returned status {response.status_code}: {body_preview}"
                    )

                data = response.json()
                response.close()

                raw_embeddings = data.get("embeddings")
                if not isinstance(raw_embeddings, list) or len(raw_embeddings) != len(inputs):
                    raise EmbeddingError(
                        f"Invalid Ollama /api/embed response: expected {len(inputs)} embedding(s), "
                        f"got {type(raw_embeddings).__name__}"
                    )

                return [self._validate_vector(vec) for vec in raw_embeddings]

            except (requests.exceptions.Timeout, requests.exceptions.ConnectionError) as e:
                if attempt + 1 < attempts:
                    wait_time = min(2 ** attempt, 30)
                    logger.warning(
                        "Ollama network error (attempt %d/%d), retrying in %ss: %s",
                        attempt + 1, attempts, wait_time, e,
                    )
                    self._sleep(wait_time, job_context)
                    continue
                raise EmbeddingError(f"Ollama request failed after {attempts} attempts: {e}") from e

            except requests.exceptions.RequestException as e:
                raise EmbeddingError(f"Ollama request error: {e}") from e

            except EmbeddingError:
                raise

            except Exception as e:
                raise EmbeddingError(f"Unexpected error generating embedding: {e}") from e

        raise EmbeddingError(f"Failed to generate embedding after {attempts} attempts")

    def embed(
        self,
        text: str,
        retry_count: Optional[int] = None,
        job_context: Optional[JobContext] = None,
    ) -> List[float]:
        """Generate an embedding vector for a single text."""
        validated = self._validate_input_text(text)
        vectors = self._post_embed([validated], retry_count=retry_count, job_context=job_context)
        return vectors[0]

    def embed_batch(
        self,
        texts: List[str],
        job_context: Optional[JobContext] = None,
    ) -> List[List[float]]:
        """Generate embeddings for multiple texts using real Ollama batching."""
        if not texts:
            return []

        validated = [self._validate_input_text(t) for t in texts]
        batch_size = max(1, self.config.embed_batch_size)
        all_vectors: List[List[float]] = []

        for start in range(0, len(validated), batch_size):
            if job_context:
                job_context.checkpoint()
            batch = validated[start : start + batch_size]
            vectors = self._post_embed(batch, job_context=job_context)
            all_vectors.extend(vectors)

        return all_vectors

    def health_check(self) -> bool:
        """Return True if Ollama answers ``/api/embed`` with a valid 768-dim vector."""
        try:
            vec = self.embed("health check", retry_count=1)
            return len(vec) == self.config.embedding_dimension
        except Exception as e:
            logger.error("Ollama health check failed: %s", e)
            return False

    def close(self) -> None:
        """Close HTTP session."""
        self.session.close()
