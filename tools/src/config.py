"""Configuration management for UTC Crawler.

Loads configuration from environment variables. Every variable declared in
``compose.yaml`` / ``.env.example`` for the crawler services is read here; there
are no "dead" variables.
"""

import os
from dataclasses import dataclass
from typing import Optional

from psycopg.conninfo import make_conninfo


def _env_str(name: str, default: str) -> str:
    value = os.environ.get(name)
    return default if value is None or value.strip() == "" else value.strip()


def _env_int(name: str, default: int) -> int:
    raw = os.environ.get(name)
    if raw is None or raw.strip() == "":
        return default
    try:
        return int(raw)
    except ValueError as e:
        raise ValueError(f"{name} must be an integer, got {raw!r}") from e


def _env_float(name: str, default: float) -> float:
    raw = os.environ.get(name)
    if raw is None or raw.strip() == "":
        return default
    try:
        return float(raw)
    except ValueError as e:
        raise ValueError(f"{name} must be a number, got {raw!r}") from e


def _env_bool(name: str, default: bool) -> bool:
    raw = os.environ.get(name)
    if raw is None or raw.strip() == "":
        return default
    value = raw.strip().lower()
    if value in ("1", "true", "yes", "on"):
        return True
    if value in ("0", "false", "no", "off"):
        return False
    raise ValueError(f"{name} must be a boolean, got {raw!r}")


@dataclass(frozen=True)
class DatabaseConfig:
    """PostgreSQL database configuration."""

    host: str
    port: int
    database: str
    user: str
    password: str
    pool_min_size: int = 1
    pool_max_size: int = 5
    pool_timeout_seconds: float = 30.0
    pool_max_waiting: int = 0
    statement_timeout_ms: int = 60_000

    def __post_init__(self):
        if self.pool_min_size < 0:
            raise ValueError("DATABASE_POOL_MIN_SIZE must be >= 0")
        if self.pool_max_size < 1:
            raise ValueError("DATABASE_POOL_MAX_SIZE must be >= 1")
        if self.pool_min_size > self.pool_max_size:
            raise ValueError("DATABASE_POOL_MIN_SIZE must be <= DATABASE_POOL_MAX_SIZE")
        if self.pool_timeout_seconds <= 0:
            raise ValueError("DATABASE_TIMEOUT_SECONDS must be > 0")
        if self.pool_max_waiting < 0:
            raise ValueError("DATABASE_MAX_WAITING must be >= 0")

    @property
    def connection_string(self) -> str:
        """Return a libpq conninfo string (properly escaped, password never in URLs)."""
        return make_conninfo(
            host=self.host,
            port=self.port,
            dbname=self.database,
            user=self.user,
            password=self.password,
            application_name="utc-crawler",
            options=f"-c statement_timeout={self.statement_timeout_ms}",
        )

    def __repr__(self) -> str:  # never leak the password in logs
        return (
            f"DatabaseConfig(host={self.host!r}, port={self.port}, database={self.database!r}, "
            f"user={self.user!r}, password='***')"
        )


@dataclass(frozen=True)
class OllamaConfig:
    """Ollama AI service configuration."""

    base_url: str
    slm_model: str
    embedding_model: str
    embedding_dimension: int
    connect_timeout_seconds: int = 5
    read_timeout_seconds: int = 120
    embed_batch_size: int = 16
    max_retries: int = 3
    max_input_chars: int = 4000


@dataclass(frozen=True)
class CrawlerConfig:
    """Crawler behavior configuration."""

    user_agent: str = "UTC-RAG-Crawler/1.0"
    default_rate_limit_seconds: float = 2.0
    request_timeout_seconds: int = 30
    max_retries: int = 3
    max_retry_after_seconds: int = 60
    max_redirects: int = 5
    max_content_size_mb: int = 50
    respect_robots_txt: bool = True
    robots_cache_ttl_seconds: int = 3600
    max_crawl_delay_seconds: float = 30.0
    max_discovery_urls: int = 200
    max_discovery_depth: int = 2
    max_sitemap_urls: int = 10000
    max_sitemap_files: int = 50
    max_pdf_pages: int = 500
    max_docx_uncompressed_mb: int = 100
    auto_publish: bool = True

    # OCR configuration (OCR is out of scope for this release; disabled by default)
    enable_ocr: bool = False
    ocr_languages: tuple = ("vi", "en")
    ocr_use_gpu: bool = False
    ocr_min_confidence: float = 0.3

    def __post_init__(self):
        if self.default_rate_limit_seconds < 0:
            raise ValueError("CRAWLER_RATE_LIMIT must be >= 0")
        if self.request_timeout_seconds <= 0:
            raise ValueError("CRAWLER_TIMEOUT must be > 0")
        if self.max_retries < 0:
            raise ValueError("CRAWLER_MAX_RETRIES must be >= 0")
        if self.max_redirects < 0:
            raise ValueError("CRAWLER_MAX_REDIRECTS must be >= 0")
        if self.max_content_size_mb <= 0:
            raise ValueError("CRAWLER_MAX_CONTENT_MB must be > 0")
        if self.max_discovery_urls <= 0 or self.max_sitemap_urls <= 0:
            raise ValueError("URL limits must be > 0")
        if self.max_discovery_depth < 0:
            raise ValueError("CRAWLER_MAX_DEPTH must be >= 0")


@dataclass(frozen=True)
class WorkerConfig:
    """Job lease / heartbeat / retry configuration."""

    lease_duration_seconds: int = 600
    heartbeat_interval_seconds: int = 120
    max_attempts: int = 3
    retry_base_delay_seconds: int = 900
    retry_max_delay_seconds: int = 21600
    retry_jitter_ratio: float = 0.2
    idle_poll_max_seconds: int = 30
    reclaim_interval_seconds: int = 60
    liveness_file: str = "/tmp/crawler-worker.alive"

    def __post_init__(self):
        if self.lease_duration_seconds < 60:
            raise ValueError("WORKER_LEASE_DURATION_SECONDS must be >= 60")
        if self.heartbeat_interval_seconds <= 0:
            raise ValueError("WORKER_HEARTBEAT_INTERVAL_SECONDS must be > 0")
        # Heartbeat must fire at least twice per lease so one missed beat is tolerated.
        if self.heartbeat_interval_seconds * 2 > self.lease_duration_seconds:
            raise ValueError(
                "WORKER_HEARTBEAT_INTERVAL_SECONDS must be <= WORKER_LEASE_DURATION_SECONDS / 2"
            )
        if self.max_attempts < 1:
            raise ValueError("JOB_MAX_ATTEMPTS must be >= 1")
        if self.retry_base_delay_seconds < 0 or self.retry_max_delay_seconds < self.retry_base_delay_seconds:
            raise ValueError("Invalid retry delays (need 0 <= base <= max)")
        if not 0 <= self.retry_jitter_ratio <= 1:
            raise ValueError("JOB_RETRY_JITTER_RATIO must be within [0, 1]")


@dataclass(frozen=True)
class SchedulerConfig:
    """Scheduler configuration."""

    check_interval_seconds: int = 300
    default_interval_hours: int = 6
    liveness_file: str = "/tmp/crawler-scheduler.alive"

    def __post_init__(self):
        if self.check_interval_seconds < 10:
            raise ValueError("SCHEDULER_CHECK_INTERVAL_SECONDS must be >= 10")
        if self.default_interval_hours < 1:
            raise ValueError("SCHEDULER_DEFAULT_INTERVAL_HOURS must be >= 1")


@dataclass(frozen=True)
class Config:
    """Complete application configuration."""

    database: DatabaseConfig
    ollama: OllamaConfig
    crawler: CrawlerConfig
    worker: WorkerConfig = WorkerConfig()
    scheduler: SchedulerConfig = SchedulerConfig()

    @classmethod
    def from_env(cls) -> "Config":
        """Load configuration from environment variables."""
        db = DatabaseConfig(
            host=_env_str("DATABASE_HOST", "localhost"),
            port=_env_int("DATABASE_PORT", 5432),
            database=_env_str("DATABASE_NAME", "utc_rag"),
            user=_env_str("DATABASE_USER", "utc_worker"),
            password=os.environ["DATABASE_PASSWORD"],  # Required
            pool_min_size=_env_int("DATABASE_POOL_MIN_SIZE", 1),
            pool_max_size=_env_int("DATABASE_POOL_MAX_SIZE", 5),
            pool_timeout_seconds=_env_float("DATABASE_TIMEOUT_SECONDS", 30.0),
            pool_max_waiting=_env_int("DATABASE_MAX_WAITING", 0),
            statement_timeout_ms=_env_int("DATABASE_STATEMENT_TIMEOUT_MS", 60_000),
        )

        ollama = OllamaConfig(
            base_url=_env_str("OLLAMA_BASE_URL", "http://localhost:11434").rstrip("/"),
            slm_model=_env_str("SLM_MODEL", "qwen3:0.6b"),
            embedding_model=_env_str("EMBEDDING_MODEL", "embeddinggemma:300m-qat-q4_0"),
            embedding_dimension=_env_int("EMBEDDING_DIMENSION", 768),
            connect_timeout_seconds=_env_int("OLLAMA_CONNECT_TIMEOUT_SECONDS", 5),
            read_timeout_seconds=_env_int("OLLAMA_TIMEOUT_SECONDS", 120),
            embed_batch_size=_env_int("OLLAMA_EMBED_BATCH_SIZE", 16),
            max_retries=_env_int("OLLAMA_MAX_RETRIES", 3),
        )

        crawler = CrawlerConfig(
            user_agent=_env_str("CRAWLER_USER_AGENT", "UTC-RAG-Crawler/1.0"),
            default_rate_limit_seconds=_env_float("CRAWLER_RATE_LIMIT", 2.0),
            request_timeout_seconds=_env_int("CRAWLER_TIMEOUT", 30),
            max_retries=_env_int("CRAWLER_MAX_RETRIES", 3),
            max_retry_after_seconds=_env_int("CRAWLER_MAX_RETRY_AFTER_SECONDS", 60),
            max_redirects=_env_int("CRAWLER_MAX_REDIRECTS", 5),
            max_content_size_mb=_env_int("CRAWLER_MAX_CONTENT_MB", 50),
            respect_robots_txt=_env_bool("CRAWLER_RESPECT_ROBOTS", True),
            robots_cache_ttl_seconds=_env_int("CRAWLER_ROBOTS_CACHE_TTL_SECONDS", 3600),
            max_crawl_delay_seconds=_env_float("CRAWLER_MAX_CRAWL_DELAY_SECONDS", 30.0),
            max_discovery_urls=_env_int("CRAWLER_MAX_URLS", 200),
            max_discovery_depth=_env_int("CRAWLER_MAX_DEPTH", 2),
            max_sitemap_urls=_env_int("CRAWLER_MAX_SITEMAP_URLS", 10000),
            max_sitemap_files=_env_int("CRAWLER_MAX_SITEMAP_FILES", 50),
            max_pdf_pages=_env_int("CRAWLER_MAX_PDF_PAGES", 500),
            max_docx_uncompressed_mb=_env_int("CRAWLER_MAX_DOCX_UNCOMPRESSED_MB", 100),
            auto_publish=_env_bool("CRAWLER_AUTO_PUBLISH", True),
            enable_ocr=_env_bool("CRAWLER_ENABLE_OCR", False),
            ocr_languages=tuple(
                lang.strip() for lang in _env_str("CRAWLER_OCR_LANGUAGES", "vi,en").split(",") if lang.strip()
            ),
            ocr_use_gpu=_env_bool("CRAWLER_OCR_USE_GPU", False),
            ocr_min_confidence=_env_float("CRAWLER_OCR_MIN_CONFIDENCE", 0.3),
        )

        worker = WorkerConfig(
            lease_duration_seconds=_env_int("WORKER_LEASE_DURATION_SECONDS", 600),
            heartbeat_interval_seconds=_env_int("WORKER_HEARTBEAT_INTERVAL_SECONDS", 120),
            max_attempts=_env_int("JOB_MAX_ATTEMPTS", 3),
            retry_base_delay_seconds=_env_int("JOB_RETRY_BASE_DELAY_SECONDS", 900),
            retry_max_delay_seconds=_env_int("JOB_RETRY_MAX_DELAY_SECONDS", 21600),
            retry_jitter_ratio=_env_float("JOB_RETRY_JITTER_RATIO", 0.2),
            idle_poll_max_seconds=_env_int("WORKER_IDLE_POLL_MAX_SECONDS", 30),
            reclaim_interval_seconds=_env_int("WORKER_RECLAIM_INTERVAL_SECONDS", 60),
            liveness_file=_env_str("WORKER_LIVENESS_FILE", "/tmp/crawler-worker.alive"),
        )

        scheduler = SchedulerConfig(
            check_interval_seconds=_env_int("SCHEDULER_CHECK_INTERVAL_SECONDS", 300),
            default_interval_hours=_env_int("SCHEDULER_DEFAULT_INTERVAL_HOURS", 6),
            liveness_file=_env_str("SCHEDULER_LIVENESS_FILE", "/tmp/crawler-scheduler.alive"),
        )

        return cls(database=db, ollama=ollama, crawler=crawler, worker=worker, scheduler=scheduler)


def load_config() -> Config:
    """Load and validate configuration from environment."""
    try:
        config = Config.from_env()
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


def touch_liveness(path: Optional[str]) -> None:
    """Update the liveness file used by the container healthcheck."""
    if not path:
        return
    try:
        with open(path, "a", encoding="utf-8"):
            pass
        os.utime(path, None)
    except OSError:
        # Liveness is advisory; failure here must not crash the worker.
        pass
