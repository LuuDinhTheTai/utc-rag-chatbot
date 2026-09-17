from fastapi import APIRouter
from app.schemas.common import SuccessResponse

router = APIRouter()

@router.get("/health", response_model=SuccessResponse)
async def health_check():
    return SuccessResponse(data={"status": "ok"})
