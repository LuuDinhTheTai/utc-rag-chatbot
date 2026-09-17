import os
import jwt
from dotenv import load_dotenv

load_dotenv(os.path.join(os.path.dirname(__file__), "../backend/.env"))

anon_key = os.getenv("SUPABASE_ANON_KEY")
jwt_secret = os.getenv("SUPABASE_JWT_SECRET")

print("Anon Key:", anon_key[:20] + "...")
print("JWT Secret:", jwt_secret)

try:
    payload = jwt.decode(anon_key, jwt_secret, algorithms=["HS256"], audience="authenticated")
    print("Success:", payload)
except Exception as e:
    print("Error:", type(e).__name__, "-", str(e))

# Also try without audience validation
try:
    payload = jwt.decode(anon_key, jwt_secret, algorithms=["HS256"], options={"verify_aud": False})
    print("Success (No Aud):", payload)
except Exception as e:
    print("Error (No Aud):", type(e).__name__, "-", str(e))
