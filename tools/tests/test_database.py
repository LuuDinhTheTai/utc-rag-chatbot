"""Tests for database connection management."""

import pytest
from unittest.mock import Mock, patch, MagicMock
from src.database import Database
from src.config import DatabaseConfig
from src.exceptions import DatabaseError


@pytest.fixture
def db_config():
    """Create test database configuration."""
    return DatabaseConfig(
        host="localhost",
        port=5432,
        database="test_db",
        user="test_user",
        password="test_password",
    )


def test_connection_string():
    """Test connection string generation."""
    config = DatabaseConfig(
        host="localhost",
        port=5432,
        database="test_db",
        user="test_user",
        password="test_password",
    )
    expected = "postgresql://test_user:test_password@localhost:5432/test_db"
    assert config.connection_string == expected


def test_database_init(db_config):
    """Test database initialization."""
    db = Database(db_config, min_size=2, max_size=10)
    assert db.config == db_config
    assert db.min_size == 2
    assert db.max_size == 10
    assert db.pool is None


@patch("src.database.ConnectionPool")
def test_connect_success(mock_pool_class, db_config):
    """Test successful database connection."""
    # Mock pool and connection
    mock_pool = MagicMock()
    mock_conn = MagicMock()
    mock_cursor = MagicMock()
    
    mock_pool_class.return_value = mock_pool
    mock_pool.connection.return_value.__enter__.return_value = mock_conn
    mock_conn.execute.return_value.fetchone.return_value = {
        "version": "PostgreSQL 15.0"
    }
    
    db = Database(db_config)
    db.connect()
    
    assert db.pool is not None
    mock_pool_class.assert_called_once()


@patch("src.database.ConnectionPool")
def test_connect_failure(mock_pool_class, db_config):
    """Test database connection failure."""
    mock_pool_class.side_effect = Exception("Connection refused")
    
    db = Database(db_config)
    with pytest.raises(DatabaseError, match="Failed to connect to database"):
        db.connect()


def test_close(db_config):
    """Test connection pool closing."""
    db = Database(db_config)
    mock_pool = MagicMock()
    db.pool = mock_pool
    
    db.close()
    
    mock_pool.close.assert_called_once()
    assert db.pool is None


def test_connection_without_pool(db_config):
    """Test connection context manager without initialized pool."""
    db = Database(db_config)
    
    with pytest.raises(DatabaseError, match="Database pool not initialized"):
        with db.connection():
            pass


@patch("src.database.ConnectionPool")
def test_transaction_rollback_on_error(mock_pool_class, db_config):
    """Test transaction rollback on exception."""
    mock_pool = MagicMock()
    mock_conn = MagicMock()
    
    mock_pool_class.return_value = mock_pool
    mock_pool.connection.return_value.__enter__.return_value = mock_conn
    mock_conn.transaction.return_value.__enter__.side_effect = Exception("Query error")
    
    db = Database(db_config)
    db.pool = mock_pool
    
    with pytest.raises(DatabaseError, match="Transaction error"):
        with db.transaction():
            pass


def test_execute_many_empty_list(db_config):
    """Test execute_many with empty parameter list."""
    db = Database(db_config)
    mock_pool = MagicMock()
    db.pool = mock_pool
    
    # Should not raise error
    db.execute_many("INSERT INTO test VALUES (%(id)s)", [])


def test_health_check_success(db_config):
    """Test health check with healthy database."""
    db = Database(db_config)
    db.execute_one = Mock(return_value={"health": 1})
    
    assert db.health_check() is True


def test_health_check_failure(db_config):
    """Test health check with database error."""
    db = Database(db_config)
    db.execute_one = Mock(side_effect=DatabaseError("Connection lost"))
    
    assert db.health_check() is False


def test_health_check_wrong_result(db_config):
    """Test health check with unexpected result."""
    db = Database(db_config)
    db.execute_one = Mock(return_value={"health": 0})
    
    assert db.health_check() is False


def test_health_check_none_result(db_config):
    """Test health check with None result."""
    db = Database(db_config)
    db.execute_one = Mock(return_value=None)
    
    assert db.health_check() is False
