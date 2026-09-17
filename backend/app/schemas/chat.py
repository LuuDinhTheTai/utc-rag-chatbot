from pydantic import BaseModel, Field
from typing import Optional, List

class ChatRequest(BaseModel):
    message: str = Field(..., min_length=1, max_length=2000)

class ChatSource(BaseModel):
    document_id: str
    file_name: str
    chunk_id: str
    similarity: float
    page: Optional[int] = None

class ChatResponse(BaseModel):
    answer: str
    sources: List[ChatSource]
