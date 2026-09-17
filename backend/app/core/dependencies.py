from fastapi import Depends, HTTPException, status
from supabase import AsyncClient
from app.core.supabase import get_supabase_client, get_supabase_service_client
from app.core.security import get_current_user_id

async def require_admin(
    user_id: str = Depends(get_current_user_id),
    supabase: AsyncClient = Depends(get_supabase_service_client)
) -> str:
    """
    Dependency that checks if the current user has the 'admin' role.
    Uses the service_client to bypass RLS for reading the profiles table 
    if RLS restricts reading to only the user's own profile.
    Returns the user_id if they are an admin.
    """
    response = await supabase.table("profiles").select("role").eq("id", user_id).maybe_single().execute()
    
    data = getattr(response, "data", None) if response else None
    if isinstance(response, dict):
        data = response.get("data") or response

    if not data or (isinstance(data, dict) and data.get("role") != "admin"):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You do not have permission to perform this action. Admin role required."
        )
    
    return user_id
