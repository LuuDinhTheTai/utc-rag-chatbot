from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials
from app.core.supabase import get_supabase_service_client
from supabase import AsyncClient

security = HTTPBearer()

async def get_current_user_id(
    credentials: HTTPAuthorizationCredentials = Depends(security),
    supabase: AsyncClient = Depends(get_supabase_service_client)
) -> str:
    """
    Verifies a Supabase JWT by calling the Supabase Auth API directly.
    This works reliably regardless of whether the token uses HS256 or ES256,
    and avoids the need to manage public keys locally.
    """
    token = credentials.credentials
    try:
        response = await supabase.auth.get_user(token)
        if response and response.user and response.user.id:
            return response.user.id
        raise Exception("User not found in token")
    except Exception as e:
        print(f"Auth verification failed: {e}")
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid authentication credentials",
            headers={"WWW-Authenticate": "Bearer"},
        )
