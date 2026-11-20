"""Ollama embedding client.

Generates embeddings using configured Ollama model with retry and validation.
"""

import logging
import time
import requests
from typing import List

from .config import OllamaConfig
from .exceptions import EmbeddingError

logger = logging.getLogger(__name__)


class OllamaEmbedder:
    """Client for generating embeddings via Ollama API."""

    def __init__(self, config: OllamaConfig):
        """Initialize Ollama client.

        Args:
            config: Ollama configuration
        """
        self.config = config
        self.embed_url = f"{config.base_url}/api/embeddings"
        self.session = requests.Session()
        self.session.headers.update(
            {
                "Content-Type": "application/json",
            }
        )

    def embed(self, text: str, retry_count: int = 3) -> List[float]:
        """Generate embedding for text.

        Args:
            text: Text to embed
            retry_count: Number of retries on failure

        Returns:
            Embedding vector as list of floats

        Raises:
            EmbeddingError: If embedding generation fails
        """
        if not text or not text.strip():
            raise EmbeddingError("Cannot embed empty text")

        for attempt in range(retry_count):
            try:
                response = self.session.post(
                    self.embed_url,
                    json={
                        "model": self.config.embedding_model,
                        "prompt": text,
                    },
                    timeout=(
                        self.config.connect_timeout_seconds,
                        self.config.read_timeout_seconds,
                    ),
                )

                if response.status_code != 200:
                    raise EmbeddingError(
                        f"Ollama API returned status {response.status_code}: {response.text}"
                    )

                result = response.json()

                # Extract embedding from Ollama response
                # Response format: {"embedding": [0.1, 0.2, ...]}
                if "embedding" not in result:
                    raise EmbeddingError(
                        f"Invalid Ollama response: missing 'embedding' field"
                    )

                embedding = result["embedding"]

                # Validate embedding
                if not isinstance(embedding, list):
                    raise EmbeddingError(
                        f"Invalid embedding type: {type(embedding)}"
                    )

                if len(embedding) != self.config.embedding_dimension:
                    raise EmbeddingError(
                        f"Embedding dimension mismatch: expected {self.config.embedding_dimension}, "
                        f"got {len(embedding)}"
                    )

                # Validate all values are finite
                if not all(isinstance(v, (int, float)) and abs(v) < float('inf') for v in embedding):
                    raise EmbeddingError("Embedding contains invalid values")

                logger.debug(
                    f"Generated embedding for {len(text)} chars "
                    f"(dim={len(embedding)}, model={self.config.embedding_model})"
                )

                return embedding

            except requests.exceptions.Timeout as e:
                if attempt < retry_count - 1:
                    wait_time = 2 ** attempt  # Exponential backoff: 1s, 2s, 4s
                    logger.warning(
                        f"Ollama timeout (attempt {attempt + 1}/{retry_count}), "
                        f"retrying in {wait_time}s: {e}"
                    )
                    time.sleep(wait_time)
                else:
                    raise EmbeddingError(f"Ollama timeout after {retry_count} attempts: {e}") from e

            except requests.exceptions.RequestException as e:
                if attempt < retry_count - 1:
                    wait_time = 2 ** attempt
                    logger.warning(
                        f"Ollama request failed (attempt {attempt + 1}/{retry_count}), "
                        f"retrying in {wait_time}s: {e}"
                    )
                    time.sleep(wait_time)
                else:
                    raise EmbeddingError(f"Ollama request failed after {retry_count} attempts: {e}") from e

            except EmbeddingError:
                # Don't retry on validation errors
                raise

            except Exception as e:
                raise EmbeddingError(f"Unexpected error generating embedding: {e}") from e

        raise EmbeddingError(f"Failed to generate embedding after {retry_count} attempts")

    def embed_batch(self, texts: List[str]) -> List[List[float]]:
        """Generate embeddings for multiple texts.

        Args:
            texts: List of texts to embed

        Returns:
            List of embedding vectors

        Raises:
            EmbeddingError: If any embedding generation fails
        """
        embeddings = []
        for i, text in enumerate(texts):
            try:
                embedding = self.embed(text)
                embeddings.append(embedding)
            except EmbeddingError as e:
                raise EmbeddingError(
                    f"Failed to embed text {i + 1}/{len(texts)}: {e}"
                ) from e

        return embeddings

    def health_check(self) -> bool:
        """Check if Ollama service is accessible.

        Returns:
            True if healthy, False otherwise
        """
        try:
            # Try to generate embedding for simple text
            test_embedding = self.embed("test", retry_count=1)
            return len(test_embedding) == self.config.embedding_dimension
        except Exception as e:
            logger.error(f"Ollama health check failed: {e}")
            return False

    def close(self) -> None:
        """Close HTTP session."""
        self.session.close()
