import asyncio
from app.core.supabase import get_supabase_service_client
from app.core.config import settings
import structlog

logger = structlog.get_logger(__name__)

async def seed_admin():
    supabase = await get_supabase_service_client()
    email = "admin@utc.edu.vn"
    password = "adminpassword123"

    print(f"Starting temp Admin creation...")
    print(f"Email: {email}")
    print(f"Password: {password}")

    try:
        user_response = await supabase.auth.admin.create_user({
            "email": email,
            "password": password,
            "email_confirm": True,
            "user_metadata": {"full_name": "UTC Admin"}
        })
        
        user_id = user_response.user.id
        print(f"User created successfully (ID: {user_id}).")

    except Exception as e:
        error_msg = str(e)
        if "already registered" in error_msg.lower() or "already exists" in error_msg.lower():
            print("User already exists in the system.")
            print("Will update the role by email.")
            user_id = None
        else:
            print(f"Error creating user: {e}")
            return

    try:
        await asyncio.sleep(1)
        
        if user_id:
            await supabase.table("profiles").update({"role": "admin"}).eq("id", user_id).execute()
        else:
            await supabase.table("profiles").update({"role": "admin"}).eq("email", email).execute()
            
        print("Successfully granted 'admin' role!")
        
    except Exception as e:
        print(f"Error granting admin role: {e}")

if __name__ == "__main__":
    asyncio.run(seed_admin())
