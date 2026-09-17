from pydantic import BaseModel
from typing import Any, Optional

class ErrorDetail(BaseModel):
    code: str
    message: str

class ErrorResponse(BaseModel):
    data: None = None
    error: ErrorDetail

class SuccessResponse(BaseModel):
    data: Any
    error: None = None
