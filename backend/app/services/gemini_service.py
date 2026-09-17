from google import genai
from google.genai import types
from app.core.config import settings
import structlog
from typing import AsyncGenerator
import asyncio

logger = structlog.get_logger(__name__)

class GeminiService:
    def __init__(self):
        self.client = genai.Client(api_key=settings.gemini_api_key)
        self.model = settings.gemini_chat_model

    async def generate(self, prompt: str, system_instruction: str) -> str:
        """Generates a complete response synchronously."""
        try:
            config = types.GenerateContentConfig(
                system_instruction=system_instruction,
                temperature=0.2, # Low temperature for RAG to be more factual
            )
            
            def _generate():
                response = self.client.models.generate_content(
                    model=self.model,
                    contents=prompt,
                    config=config
                )
                return response.text
                
            return await asyncio.to_thread(_generate)
            
        except Exception as e:
            logger.error("gemini_generation_failed", error=str(e))
            raise ValueError(f"Failed to generate content: {str(e)}")

    async def generate_stream(self, prompt: str, system_instruction: str) -> AsyncGenerator[str, None]:
        """Generates a response as a stream."""
        try:
            config = types.GenerateContentConfig(
                system_instruction=system_instruction,
                temperature=0.2,
            )
            
            # Streaming from genai is a generator
            response_stream = self.client.models.generate_content_stream(
                model=self.model,
                contents=prompt,
                config=config
            )
            
            # We need to yield from it. If it's a sync generator, we wrap it.
            for chunk in response_stream:
                if chunk.text:
                    yield chunk.text
                    await asyncio.sleep(0.01) # Small sleep to yield to event loop
                    
        except Exception as e:
            logger.error("gemini_streaming_failed", error=str(e))
            raise ValueError(f"Failed to stream content: {str(e)}")
