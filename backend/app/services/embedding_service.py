from google import genai
from google.genai import types
from app.core.config import settings
import structlog
from typing import List

logger = structlog.get_logger(__name__)

class EmbeddingService:
    def __init__(self):
        self.client = genai.Client(api_key=settings.gemini_api_key)
        self.model = settings.gemini_embedding_model
        self.dimension = settings.gemini_embedding_dimension

    async def embed_text(self, text: str, task_type: str = "RETRIEVAL_QUERY") -> List[float]:
        """Embeds a single string."""
        embeddings = await self.embed_texts([text], task_type=task_type)
        return embeddings[0] if embeddings else []

    async def embed_texts(self, texts: List[str], task_type: str = "RETRIEVAL_DOCUMENT") -> List[List[float]]:
        """Embeds a list of strings using batching if needed."""
        if not texts:
            return []
            
        try:
            # We use output_dimensionality to reduce size based on config
            config = types.EmbedContentConfig(
                task_type=task_type,
                output_dimensionality=self.dimension
            )
            
            # The python SDK currently might not support async natively in all methods,
            # but we'll use the sync method in a thread or directly if it supports it.
            # Currently `embed_content` is synchronous in the basic client, we can use run_in_executor if needed,
            # but for now we'll call it directly. In production, consider asyncio.to_thread
            import asyncio
            
            def _embed():
                response = self.client.models.embed_content(
                    model=self.model,
                    contents=texts,
                    config=config
                )
                # response.embeddings is a list of EmbedContentResponse objects
                return [e.values for e in response.embeddings]
                
            return await asyncio.to_thread(_embed)
            
        except Exception as e:
            logger.error("embedding_failed", error=str(e))
            raise ValueError(f"Failed to generate embeddings: {str(e)}")
