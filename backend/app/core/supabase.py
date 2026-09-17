from supabase import create_async_client, AsyncClient
from app.core.config import settings
import structlog

logger = structlog.get_logger(__name__)


async def get_supabase_client() -> AsyncClient:
    """
    Returns an async Supabase client initialized with the anonymous key.
    Useful for operations that act on behalf of a specific user with their JWT.
    """
    return await create_async_client(settings.supabase_url, settings.supabase_anon_key)


async def get_supabase_service_client() -> AsyncClient:
    """
    Returns an async Supabase client initialized with the service role key.
    WARNING: This bypasses Row Level Security (RLS). Use only for server-side admin operations.
    """
    return await create_async_client(settings.supabase_url, settings.supabase_service_role_key)
