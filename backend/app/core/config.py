from pydantic_settings import BaseSettings, SettingsConfigDict
from typing import List


class Settings(BaseSettings):
    app_env: str = "development"
    
    # Supabase
    supabase_url: str
    supabase_anon_key: str
    supabase_service_role_key: str
    supabase_jwt_secret: str

    # Gemini API
    gemini_api_key: str
    gemini_chat_model: str = "gemini-2.0-flash"
    gemini_embedding_model: str = "gemini-embedding-001"
    gemini_embedding_dimension: int = 768

    # RAG settings
    rag_top_k: int = 5
    rag_similarity_threshold: float = 0.7
    
    # Chunking settings
    chunk_size: int = 800
    chunk_overlap: int = 100
    
    # Upload settings
    max_file_size_mb: int = 20
    
    # CORS
    cors_origins: List[str] = ["http://localhost:3000", "http://localhost:3001"]

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )


settings = Settings()
