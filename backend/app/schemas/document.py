from pydantic import BaseModel
from datetime import datetime
from typing import Optional, List

class DocumentResponse(BaseModel):
    id: str
    file_name: str
    file_type: str
    file_size: int
    status: str
    error_message: Optional[str] = None
    created_at: datetime

class DocumentListResponse(BaseModel):
    documents: List[DocumentResponse]
