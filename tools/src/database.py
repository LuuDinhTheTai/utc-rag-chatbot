"""Database connection and query management.

Provides a PostgreSQL connection pool and transaction handling using psycopg3.

Rules:
* Any operation that must be atomic (ownership check + write) MUST be performed
  inside a single ``transaction()`` block and use the yielded connection. The
  helpers ``execute`` / ``execute_one`` each borrow their own connection and
  commit immediately; they must never be used to "protect" a later statement.
* Domain exceptions (``CrawlerException`` subclasses such as
  ``LeaseExpiredError``) raised inside a transaction are re-raised unchanged after
  rollback, so callers can distinguish lost ownership from database failures.
* SQL parameters are never logged (they may contain document content or tokens).
"""

import logging
from contextlib import contextmanager
from typing import Any, Dict, List, Optional, Sequence, Union

import psycopg
from psycopg.rows import dict_row
from psycopg_pool import ConnectionPool

try:
    from pgvector.psycopg import register_vector
except ImportError:  # pragma: no cover - verified at runtime on connect()
    register_vector = None

from .config import DatabaseConfig
from .exceptions import CrawlerException, DatabaseError

logger = logging.getLogger(__name__)

Params = Optional[Union[Dict[str, Any], Sequence[Any]]]


def _short(query: str, limit: int = 200) -> str:
    compact = " ".join(query.split())
    return compact if len(compact) <= limit else compact[:limit] + "..."


class Database:
    """PostgreSQL database connection manager with connection pooling."""

    def __init__(
        self,
        config: DatabaseConfig,
        min_size: Optional[int] = None,
        max_size: Optional[int] = None,
        timeout: Optional[float] = None,
        max_waiting: Optional[int] = None,
    ):
        """Initialize database connection pool settings (pool opens on ``connect``)."""
        self.config = config
        self.pool: Optional[ConnectionPool] = None
        self.min_size = config.pool_min_size if min_size is None else min_size
        self.max_size = config.pool_max_size if max_size is None else max_size
        self.timeout = config.pool_timeout_seconds if timeout is None else timeout
        self.max_waiting = config.pool_max_waiting if max_waiting is None else max_waiting

        if self.min_size < 0 or self.max_size < 1 or self.min_size > self.max_size:
            raise ValueError(
                f"Invalid pool size: min={self.min_size}, max={self.max_size}"
            )
        if self.timeout <= 0:
            raise ValueError("Pool timeout must be > 0")

    @staticmethod
    def _configure_connection(conn: psycopg.Connection) -> None:
        """Register pgvector adapter on every pooled connection (for both INSERT and SELECT)."""
        if register_vector is None:
            raise DatabaseError(
                "pgvector package is not installed; install pgvector to support vector(768) columns"
            )
        register_vector(conn)

    def connect(self) -> None:
        """Initialize connection pool and verify connectivity.

        Raises:
            DatabaseError: If connection fails (the pool is closed again).
        """
        if self.pool is not None:
            return
        logger.info(
            "Connecting to PostgreSQL at %s:%s/%s as %s",
            self.config.host, self.config.port, self.config.database, self.config.user,
        )
        pool = ConnectionPool(
            conninfo=self.config.connection_string,
            min_size=self.min_size,
            max_size=self.max_size,
            timeout=self.timeout,
            max_waiting=self.max_waiting,
            kwargs={"row_factory": dict_row},
            configure=self._configure_connection,
            open=False,
        )
        try:
            pool.open(wait=False)
            with pool.connection(timeout=self.timeout) as conn:
                row = conn.execute("SELECT version() AS version, current_user AS usr").fetchone()
                logger.info("Connected to PostgreSQL as %s: %s", row["usr"], row["version"][:60])
        except Exception as e:
            pool.close()
            raise DatabaseError(f"Failed to connect to database: {e}") from e
        self.pool = pool

    def close(self) -> None:
        """Close connection pool (idempotent)."""
        pool, self.pool = self.pool, None
        if pool is not None:
            logger.info("Closing database connection pool")
            pool.close()

    @contextmanager
    def connection(self):
        """Borrow a connection from the pool (commits on success, rolls back on error)."""
        if not self.pool:
            raise DatabaseError("Database pool not initialized. Call connect() first.")
        try:
            with self.pool.connection() as conn:
                yield conn
        except CrawlerException:
            raise
        except psycopg.Error as e:
            raise DatabaseError(f"Database error: {e}") from e

    @contextmanager
    def transaction(self):
        """Yield a connection inside one explicit transaction.

        Commits on success, rolls back on any exception. Domain exceptions are
        re-raised unchanged; driver errors are wrapped in ``DatabaseError``.
        """
        with self.connection() as conn:
            try:
                with conn.transaction():
                    yield conn
            except CrawlerException:
                raise
            except psycopg.Error as e:
                logger.error("Transaction rolled back: %s", e)
                raise DatabaseError(f"Transaction error: {e}") from e

    def execute(self, query: str, params: Params = None) -> List[Dict[str, Any]]:
        """Execute a query in its own short transaction and return all rows."""
        try:
            with self.connection() as conn:
                with conn.cursor() as cur:
                    cur.execute(query, params)
                    return cur.fetchall() if cur.description else []
        except DatabaseError:
            logger.error("Query failed: %s", _short(query))
            raise

    def execute_one(self, query: str, params: Params = None) -> Optional[Dict[str, Any]]:
        """Execute a query in its own short transaction and return the first row."""
        try:
            with self.connection() as conn:
                with conn.cursor() as cur:
                    cur.execute(query, params)
                    return cur.fetchone() if cur.description else None
        except DatabaseError:
            logger.error("Query failed: %s", _short(query))
            raise

    def execute_batch(self, query: str, params_list: List[Dict[str, Any]]) -> None:
        """Execute a statement for many parameter sets in one transaction."""
        if not params_list:
            return
        try:
            with self.transaction() as conn:
                with conn.cursor() as cur:
                    cur.executemany(query, params_list)
        except DatabaseError:
            logger.error("Batch query failed: %s", _short(query))
            raise

    def health_check(self) -> bool:
        """Return True if the database answers a trivial query."""
        try:
            result = self.execute_one("SELECT 1 AS health")
            return result is not None and result.get("health") == 1
        except Exception as e:
            logger.error("Health check failed: %s", e)
            return False
