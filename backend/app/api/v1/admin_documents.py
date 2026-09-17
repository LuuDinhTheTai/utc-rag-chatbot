from fastapi import APIRouter, Depends, UploadFile, File, HTTPException, status
from typing import List

from app.schemas.document import DocumentResponse, DocumentListResponse
from app.schemas.common import SuccessResponse
from app.services.document_service import DocumentService
from app.core.dependencies import require_admin
from app.core.config import settings

router = APIRouter()
document_service = DocumentService()

ALLOWED_MIME_TYPES = [
    "application/pdf",
    "text/plain",
    "text/markdown",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
]

@router.post("", response_model=SuccessResponse)
async def upload_document(
    file: UploadFile = File(...),
    admin_id: str = Depends(require_admin)
):
    """Upload a document to the knowledge base."""
    # 1. Validate MIME type
    if file.content_type not in ALLOWED_MIME_TYPES:
        raise HTTPException(status_code=400, detail="Unsupported file type.")
        
    # 2. Upload and process
    try:
        doc = await document_service.upload(file, admin_id)
        return SuccessResponse(data=doc.model_dump())
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@router.get("", response_model=SuccessResponse)
async def list_documents(admin_id: str = Depends(require_admin)):
    """List all documents in the knowledge base."""
    try:
        docs = await document_service.list_documents()
        return SuccessResponse(data={"documents": [d.model_dump() for d in docs]})
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@router.delete("/{document_id}", response_model=SuccessResponse)
async def delete_document(
    document_id: str,
    admin_id: str = Depends(require_admin)
):
    """Delete a document and its chunks from the knowledge base."""
    try:
        await document_service.delete(document_id)
        return SuccessResponse(data={"message": "Document deleted successfully"})
    except ValueError as e:
        raise HTTPException(status_code=404, detail=str(e))
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@router.post("/{document_id}/reindex", response_model=SuccessResponse)
async def reindex_document(
    document_id: str,
    admin_id: str = Depends(require_admin)
):
    """Re-index an existing document."""
    try:
        await document_service.reindex(document_id)
        return SuccessResponse(data={"message": "Document re-indexing started"})
    except ValueError as e:
        raise HTTPException(status_code=404, detail=str(e))
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
