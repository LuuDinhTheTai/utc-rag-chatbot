"""Database connection and query management.

Provides PostgreSQL connection pool and transaction handling using psycopg3.
"""

import logging
from typing import Any, Optional, Dict, List
from contextlib import contextmanager
import psycopg
from psycopg.rows import dict_row
from psycopg_pool import ConnectionPool

from .config import DatabaseConfig
from .exceptions import DatabaseError

logger = logging.getLogger(__name__)


class Database:
    """PostgreSQL database connection manager with connection pooling."""

    def __init__(
        self,
        config: DatabaseConfig,
        min_size: int = 2,
        max_size: int = 10,
        timeout: float = 30.0,
        max_waiting: int = 0,
    ):
        """Initialize database connection pool.

        Args:
            config: Database configuration
            min_size: Minimum pool size
            max_size: Maximum pool size
            timeout: Connection timeout in seconds
            max_waiting: Maximum number of requests waiting for a connection (0 = unlimited)
        """
        self.config = config
        self.pool: Optional[ConnectionPool] = None
        self.min_size = min_size
        self.max_size = max_size
        self.timeout = timeout
        self.max_waiting = max_waiting

    def connect(self) -> None:
        """Initialize connection pool.

        Raises:
            DatabaseError: If connection fails
        """
        try:
            logger.info(
                f"Connecting to PostgreSQL at {self.config.host}:{self.config.port}/{self.config.database}"
            )

            self.pool = ConnectionPool(
                conninfo=self.config.connection_string,
                min_size=self.min_size,
                max_size=self.max_size,
                timeout=self.timeout,
                max_waiting=self.max_waiting,
                kwargs={"row_factory": dict_row},
                open=True,
            )

            # Test connection
            with self.pool.connection() as conn:
                result = conn.execute("SELECT version()").fetchone()
                logger.info(f"Connected to PostgreSQL: {result['version'][:50]}...")

        except Exception as e:
            raise DatabaseError(f"Failed to connect to database: {e}") from e

    def close(self) -> None:
        """Close connection pool."""
        if self.pool:
            logger.info("Closing database connection pool")
            self.pool.close()
            self.pool = None

    @contextmanager
    def connection(self):
        """Get a connection from the pool.

        Yields:
            psycopg.Connection

        Raises:
            DatabaseError: If pool not initialized
        """
        if not self.pool:
            raise DatabaseError("Database pool not initialized. Call connect() first.")

        with self.pool.connection() as conn:
            try:
                yield conn
            except Exception as e:
                logger.error(f"Database connection error: {e}")
                raise DatabaseError(f"Connection error: {e}") from e

    @contextmanager
    def transaction(self):
        """Get a connection with transaction context.

        Automatically commits on success, rolls back on exception.

        Yields:
            psycopg.Connection

        Raises:
            DatabaseError: If transaction fails
        """
        with self.connection() as conn:
            try:
                with conn.transaction():
                    yield conn
            except Exception as e:
                logger.error(f"Transaction failed: {e}")
                raise DatabaseError(f"Transaction error: {e}") from e

    def execute(
        self, query: str, params: Optional[Dict[str, Any]] = None
    ) -> List[Dict[str, Any]]:
        """Execute query and return all results.

        Args:
            query: SQL query
            params: Query parameters

        Returns:
            List of result rows as dicts

        Raises:
            DatabaseError: If query fails
        """
        try:
            with self.connection() as conn:
                with conn.cursor() as cur:
                    cur.execute(query, params or {})
                    if cur.description:  # Has results
                        return cur.fetchall()
                    return []
        except Exception as e:
            logger.error(f"Query failed: {e}\nQuery: {query}\nParams: {params}")
            raise DatabaseError(f"Query failed: {e}") from e

    def execute_one(
        self, query: str, params: Optional[Dict[str, Any]] = None
    ) -> Optional[Dict[str, Any]]:
        """Execute query and return single result.

        Args:
            query: SQL query
            params: Query parameters

        Returns:
            Single result row as dict, or None

        Raises:
            DatabaseError: If query fails
        """
        try:
            with self.connection() as conn:
                with conn.cursor() as cur:
                    cur.execute(query, params or {})
                    if cur.description:
                        return cur.fetchone()
                    return None
        except Exception as e:
            logger.error(f"Query failed: {e}\nQuery: {query}\nParams: {params}")
            raise DatabaseError(f"Query failed: {e}") from e

    def execute_many(
        self, query: str, params_list: List[Dict[str, Any]]
    ) -> None:
        """Execute query multiple times with different parameters.

        Uses executemany for efficiency when inserting multiple rows.

        Args:
            query: SQL query with named placeholders
            params_list: List of parameter dicts

        Raises:
            DatabaseError: If query fails
        """
        if not params_list:
            return

        try:
            with self.connection() as conn:
                with conn.cursor() as cur:
                    # psycopg3 executemany with named parameters
                    cur.executemany(query, params_list)
        except Exception as e:
            logger.error(f"Batch query failed: {e}\nQuery: {query}")
            raise DatabaseError(f"Batch query failed: {e}") from e

    def health_check(self) -> bool:
        """Check if database is accessible.

        Returns:
            True if healthy, False otherwise
        """
        try:
            result = self.execute_one("SELECT 1 as health")
            return result is not None and result.get("health") == 1
        except Exception as e:
            logger.error(f"Health check failed: {e}")
            return False
