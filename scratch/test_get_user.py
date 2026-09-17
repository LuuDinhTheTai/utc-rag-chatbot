import asyncio
import os
from dotenv import load_dotenv
from supabase import create_client, Client

load_dotenv(os.path.join(os.path.dirname(__file__), "../backend/.env"))

supabase_url = os.getenv("SUPABASE_URL")
supabase_key = os.getenv("SUPABASE_ANON_KEY")

# Create sync client just for testing
supabase: Client = create_client(supabase_url, supabase_key)

def test():
    # Pass a dummy token or None just to see the method signature
    try:
        res = supabase.auth.get_user("dummy_token")
        print(res)
    except Exception as e:
        print("Error:", type(e).__name__, "-", str(e))

test()
