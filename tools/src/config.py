"""Configuration management for UTC Crawler.

Loads configuration from environment variables without hardcoding stakeholder-specific values.
"""

import os
from dataclasses import dataclass
from typing import Optional


@dataclass(frozen=True)
class DatabaseConfig:
    """PostgreSQL database configuration."""

    host: str
    port: int
    database: str
    user: str
    password: str

    @property
    def connection_string(self) -> str:
        """Return psycopg connection string."""
        return f"postgresql://{self.user}:{self.password}@{self.host}:{self.port}/{self.database}"


@dataclass(frozen=True)
class OllamaConfig:
    """Ollama AI service configuration."""

    base_url: str
    slm_model: str
    embedding_model: str
    embedding_dimension: int
    connect_timeout_seconds: int = 5
    read_timeout_seconds: int = 120


@dataclass(frozen=True)
class CrawlerConfig:
    """Crawler behavior configuration."""

    user_agent: str = "UTC-RAG-Crawler/1.0"
    default_rate_limit_seconds: float = 2.0  # Conservative default
    request_timeout_seconds: int = 30
    max_retries: int = 3
    max_content_size_mb: int = 50
    respect_robots_txt: bool = True


@dataclass(frozen=True)
class Config:
    """Complete application configuration."""

    database: DatabaseConfig
    ollama: OllamaConfig
    crawler: CrawlerConfig

    @classmethod
    def from_env(cls) -> "Config":
        """Load configuration from environment variables."""
        db = DatabaseConfig(
            host=os.environ.get("POSTGRES_HOST", "localhost"),
            port=int(os.environ.get("POSTGRES_PORT", "5432")),
            database=os.environ.get("POSTGRES_DB", "utc_rag"),
            user=os.environ.get("POSTGRES_USER", "utc"),
            password=os.environ["POSTGRES_PASSWORD"],  # Required
        )

        ollama = OllamaConfig(
            base_url=os.environ.get("OLLAMA_BASE_URL", "http://localhost:11434"),
            slm_model=os.environ.get("SLM_MODEL", "qwen3:0.6b"),
            embedding_model=os.environ.get(
                "EMBEDDING_MODEL", "embeddinggemma:300m-qat-q4_0"
            ),
            embedding_dimension=int(os.environ.get("EMBEDDING_DIMENSION", "768")),
        )

        crawler = CrawlerConfig(
            user_agent=os.environ.get("CRAWLER_USER_AGENT", "UTC-RAG-Crawler/1.0"),
            default_rate_limit_seconds=float(
                os.environ.get("CRAWLER_RATE_LIMIT", "2.0")
            ),
            request_timeout_seconds=int(os.environ.get("CRAWLER_TIMEOUT", "30")),
            max_retries=int(os.environ.get("CRAWLER_MAX_RETRIES", "3")),
            max_content_size_mb=int(os.environ.get("CRAWLER_MAX_CONTENT_MB", "50")),
            respect_robots_txt=os.environ.get("CRAWLER_RESPECT_ROBOTS", "true").lower()
            == "true",
        )

        return cls(database=db, ollama=ollama, crawler=crawler)


def load_config() -> Config:
    """Load and validate configuration from environment."""
    try:
        config = Config.from_env()
        # Validate embedding dimension matches schema
        if config.ollama.embedding_dimension != 768:
            raise ValueError(
                f"Embedding dimension must be 768 (got {config.ollama.embedding_dimension}). "
                "This is a fixed architectural constraint."
            )
        return config
    except KeyError as e:
        raise RuntimeError(f"Missing required environment variable: {e}") from e
    except ValueError as e:
        raise RuntimeError(f"Invalid configuration: {e}") from e
