from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
import structlog

from app.core.config import settings
from app.utils.logging import setup_logging
from app.api.v1 import health, chat, admin_documents

# Initialize logging
setup_logging()
logger = structlog.get_logger(__name__)

app = FastAPI(
    title="UTC RAG Chatbot API",
    description="Backend API for the UTC RAG Chatbot",
    version="1.0.0"
)

# CORS middleware
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origins,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Global exception handler
@app.exception_handler(Exception)
async def global_exception_handler(request: Request, exc: Exception):
    logger.error("unhandled_exception", error=str(exc), path=request.url.path)
    return JSONResponse(
        status_code=500,
        content={"data": None, "error": {"code": "INTERNAL_ERROR", "message": "An internal server error occurred."}}
    )

# Include routers
app.include_router(health.router, prefix="/api/v1/health", tags=["Health"])
app.include_router(chat.router, prefix="/api/v1/chat", tags=["Chat"])
app.include_router(admin_documents.router, prefix="/api/v1/admin/documents", tags=["Admin Documents"])

@app.on_event("startup")
async def startup_event():
    logger.info("application_started", env=settings.app_env)

@app.on_event("shutdown")
async def shutdown_event():
    logger.info("application_shutdown")
