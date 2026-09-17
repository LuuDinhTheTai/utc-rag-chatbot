import asyncio
import uuid
import structlog
from typing import List, Dict, Any, Optional
from datetime import datetime
from fastapi import UploadFile

from app.core.supabase import get_supabase_service_client
from app.core.config import settings
from app.schemas.document import DocumentResponse
from app.services.storage_service import StorageService
from app.services.embedding_service import EmbeddingService
from app.services.vector_service import VectorService
from app.utils.chunking import TextChunker
from app.parsers.parser_factory import ParserFactory

logger = structlog.get_logger(__name__)

class DocumentService:
    def __init__(self):
        self.storage_service = StorageService()
        self.embedding_service = EmbeddingService()
        self.vector_service = VectorService()
        self.chunker = TextChunker(chunk_size=settings.chunk_size, chunk_overlap=settings.chunk_overlap)

    async def list_documents(self) -> List[DocumentResponse]:
        supabase = await get_supabase_service_client()
        # Count chunks for each document
        
        # A simpler way is to just get documents and we can assume chunk count if needed or query it separately.
        # For MVP, let's just get the documents.
        response = await supabase.table("documents").select("*").order("created_at", desc=True).execute()
        
        docs = []
        for d in response.data:
            # We'll set chunk_count to 0 here for performance, or we could do a join/count.
            # To keep it simple, we just map the fields.
            docs.append(DocumentResponse(
                id=d["id"],
                file_name=d["file_name"],
                file_type=d["file_type"],
                file_size=d["file_size"],
                status=d["status"],
                error_message=d["error_message"],
                created_at=d["created_at"],
                chunk_count=0 # Placeholder
            ))
        return docs

    async def delete(self, document_id: str):
        supabase = await get_supabase_service_client()
        
        # 1. Get doc to find file path
        doc_resp = await supabase.table("documents").select("file_path").eq("id", document_id).maybe_single().execute()
        if not doc_resp.data:
            raise ValueError("Document not found")
            
        file_path = doc_resp.data["file_path"]
        
        # 2. Delete from storage
        try:
            await self.storage_service.delete_file(file_path)
        except Exception as e:
            logger.warning("storage_delete_failed_during_doc_delete", error=str(e))
            
        # 3. Delete from DB (this will cascade delete chunks)
        await supabase.table("documents").delete().eq("id", document_id).execute()

    async def upload(self, file: UploadFile, user_id: str) -> DocumentResponse:
        supabase = await get_supabase_service_client()
        document_id = str(uuid.uuid4())
        
        # Validate size
        file_bytes = await file.read()
        file_size = len(file_bytes)
        
        # Create record
        file_name = file.filename
        file_type = file.content_type
        file_path = f"{document_id}/{file_name}"
        
        doc_data = {
            "id": document_id,
            "uploaded_by": user_id,
            "file_name": file_name,
            "file_path": file_path,
            "file_type": file_type,
            "file_size": file_size,
            "status": "pending"
        }
        
        resp = await supabase.table("documents").insert(doc_data).execute()
        doc = resp.data[0]
        
        # Upload to storage
        await self.storage_service.upload_file(file_bytes, file_name, document_id)
        
        # Start background processing
        asyncio.create_task(self._process_document(document_id, file_bytes, file_name, file_type))
        
        return DocumentResponse(
            id=doc["id"],
            file_name=doc["file_name"],
            file_type=doc["file_type"],
            file_size=doc["file_size"],
            status=doc["status"],
            error_message=None,
            created_at=doc["created_at"],
            chunk_count=0
        )

    async def reindex(self, document_id: str):
        supabase = await get_supabase_service_client()
        doc_resp = await supabase.table("documents").select("*").eq("id", document_id).maybe_single().execute()
        
        if not doc_resp.data:
            raise ValueError("Document not found")
            
        doc = doc_resp.data
        
        # Set status to pending/processing
        await supabase.table("documents").update({"status": "processing", "error_message": None}).eq("id", document_id).execute()
        
        # Delete old chunks
        await self.vector_service.delete_chunks_by_document(document_id)
        
        # Download file
        file_bytes = await self.storage_service.download_file(doc["file_path"])
        
        # Start background processing
        asyncio.create_task(self._process_document(document_id, file_bytes, doc["file_name"], doc["file_type"]))

    async def _process_document(self, document_id: str, file_bytes: bytes, file_name: str, file_type: str):
        supabase = await get_supabase_service_client()
        try:
            await supabase.table("documents").update({"status": "processing"}).eq("id", document_id).execute()
            
            # 1. Parse
            parser = ParserFactory.get_parser(file_type, file_name)
            parsed_pages = await parser.parse(file_bytes)
            
            # 2. Chunk
            all_chunks = []
            for page in parsed_pages:
                page_chunks = self.chunker.chunk(
                    text=page.content, 
                    page=page.page_number,
                    base_metadata={"file_name": file_name}
                )
                all_chunks.extend(page_chunks)
                
            if not all_chunks:
                raise ValueError("No text extracted from document")
                
            # 3. Embed (batching logic can be added here if needed, Gemini might limit payload size)
            # For MVP we embed all at once assuming they fit.
            texts_to_embed = [c.content for c in all_chunks]
            embeddings = await self.embedding_service.embed_texts(texts_to_embed)
            
            # 4. Insert chunks
            chunks_data = []
            for i, chunk in enumerate(all_chunks):
                chunks_data.append({
                    "document_id": document_id,
                    "content": chunk.content,
                    "chunk_index": chunk.chunk_index,
                    "embedding": embeddings[i],
                    "metadata": {**chunk.metadata, "page": chunk.page}
                })
                
            # Insert in batches of 100 to avoid request size limits
            batch_size = 100
            for i in range(0, len(chunks_data), batch_size):
                batch = chunks_data[i:i+batch_size]
                await self.vector_service.insert_chunks(document_id, batch)
                
            # 5. Mark completed
            await supabase.table("documents").update({"status": "completed", "error_message": None}).eq("id", document_id).execute()
            
        except Exception as e:
            logger.error("document_processing_failed", document_id=document_id, error=str(e))
            await supabase.table("documents").update({
                "status": "failed", 
                "error_message": str(e)
            }).eq("id", document_id).execute()
