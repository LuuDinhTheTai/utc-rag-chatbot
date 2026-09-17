from fastapi import APIRouter, HTTPException
from fastapi.responses import StreamingResponse
import json
import structlog

from app.schemas.chat import ChatRequest, ChatResponse
from app.schemas.common import SuccessResponse
from app.services.rag_service import RAGService

logger = structlog.get_logger(__name__)
router = APIRouter()
rag_service = RAGService()

@router.post("", response_model=SuccessResponse)
async def chat(request: ChatRequest):
    """
    Standard chat endpoint (non-streaming).
    No authentication required.
    Does not save chat history.
    """
    try:
        response: ChatResponse = await rag_service.answer(request.message)
        return SuccessResponse(data=response.model_dump())
    except Exception as e:
        logger.error("chat_error", error=str(e))
        raise HTTPException(status_code=500, detail="An error occurred while processing your request.")

@router.post("/stream")
async def chat_stream(request: ChatRequest):
    """
    Streaming chat endpoint using Server-Sent Events (SSE).
    No authentication required.
    Does not save chat history.
    """
    async def event_generator():
        try:
            async for chunk in rag_service.answer_stream(request.message):
                yield f"data: {json.dumps(chunk)}\n\n"
        except Exception as e:
            logger.error("chat_stream_error", error=str(e))
            yield f"data: {json.dumps({'type': 'error', 'content': 'An error occurred.'})}\n\n"

    return StreamingResponse(event_generator(), media_type="text/event-stream")
