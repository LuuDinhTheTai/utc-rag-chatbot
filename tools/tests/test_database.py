"""Tests for database connection management."""

import psycopg
import pytest
from unittest.mock import MagicMock, Mock, patch

from src.config import DatabaseConfig
from src.database import Database
from src.exceptions import DatabaseError, LeaseExpiredError


@pytest.fixture
def db_config():
    return DatabaseConfig(
        host="localhost",
        port=5432,
        database="test_db",
        user="test_user",
        password="test_password",
    )


def test_connection_string(db_config):
    conn_str = db_config.connection_string
    assert "host=localhost" in conn_str
    assert "port=5432" in conn_str
    assert "dbname=test_db" in conn_str
    assert "user=test_user" in conn_str
    assert "statement_timeout=" in conn_str
    assert "test_password" not in repr(db_config)


def test_database_init(db_config):
    db = Database(db_config, min_size=2, max_size=10)
    assert db.config == db_config
    assert db.min_size == 2
    assert db.max_size == 10
    assert db.pool is None


def test_database_invalid_pool_sizes(db_config):
    with pytest.raises(ValueError):
        Database(db_config, min_size=5, max_size=2)
    with pytest.raises(ValueError):
        Database(db_config, timeout=0)


@patch("src.database.ConnectionPool")
def test_connect_success(mock_pool_class, db_config):
    mock_pool = MagicMock()
    mock_conn = MagicMock()
    mock_pool_class.return_value = mock_pool
    mock_pool.connection.return_value.__enter__.return_value = mock_conn
    mock_conn.execute.return_value.fetchone.return_value = {
        "version": "PostgreSQL 17.0",
        "usr": "test_user",
    }

    db = Database(db_config)
    db.connect()

    assert db.pool is mock_pool
    mock_pool.open.assert_called_once_with(wait=False)
    _, kwargs = mock_pool_class.call_args
    assert kwargs["configure"] == db._configure_connection


def test_configure_connection_registers_pgvector_adapter():
    mock_conn = MagicMock()
    with patch("src.database.register_vector") as mock_reg:
        Database._configure_connection(mock_conn)
        mock_reg.assert_called_once_with(mock_conn)

    with patch("src.database.register_vector", None):
        with pytest.raises(DatabaseError, match="pgvector package is not installed"):
            Database._configure_connection(mock_conn)


@patch("src.database.ConnectionPool")
def test_connect_failure_closes_pool(mock_pool_class, db_config):
    mock_pool = MagicMock()
    mock_pool.connection.side_effect = psycopg.OperationalError("Connection refused")
    mock_pool_class.return_value = mock_pool

    db = Database(db_config)
    with pytest.raises(DatabaseError, match="Failed to connect to database"):
        db.connect()

    mock_pool.close.assert_called_once()
    assert db.pool is None


def test_close(db_config):
    db = Database(db_config)
    mock_pool = MagicMock()
    db.pool = mock_pool

    db.close()

    mock_pool.close.assert_called_once()
    assert db.pool is None


def test_connection_without_pool(db_config):
    db = Database(db_config)
    with pytest.raises(DatabaseError, match="Database pool not initialized"):
        with db.connection():
            pass


def test_transaction_wraps_driver_error_and_preserves_domain_error(db_config):
    mock_pool = MagicMock()
    mock_conn = MagicMock()
    mock_pool.connection.return_value.__enter__.return_value = mock_conn
    mock_conn.transaction.return_value.__enter__.return_value = None
    mock_conn.transaction.return_value.__exit__.return_value = False

    db = Database(db_config)
    db.pool = mock_pool

    # Domain error propagates unchanged
    with pytest.raises(LeaseExpiredError):
        with db.transaction():
            raise LeaseExpiredError("expired")

    # Driver error is wrapped in DatabaseError
    with pytest.raises(DatabaseError, match="Transaction error"):
        with db.transaction():
            raise psycopg.OperationalError("boom")


def test_execute_batch_empty_list(db_config):
    db = Database(db_config)
    db.pool = MagicMock()
    db.execute_batch("INSERT INTO test VALUES (%(id)s)", [])


def test_health_check(db_config):
    db = Database(db_config)
    db.execute_one = Mock(return_value={"health": 1})
    assert db.health_check() is True

    db.execute_one = Mock(side_effect=DatabaseError("Connection lost"))
    assert db.health_check() is False

    db.execute_one = Mock(return_value={"health": 0})
    assert db.health_check() is False

    db.execute_one = Mock(return_value=None)
    assert db.health_check() is False
