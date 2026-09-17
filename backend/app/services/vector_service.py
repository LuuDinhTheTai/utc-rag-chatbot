from app.core.supabase import get_supabase_service_client
from app.core.config import settings
import structlog
from typing import List, Dict, Any

logger = structlog.get_logger(__name__)

class VectorService:
    async def search(self, query_embedding: List[float], top_k: int = settings.rag_top_k, threshold: float = settings.rag_similarity_threshold) -> List[Dict[str, Any]]:
        """
        Searches for similar document chunks using the pgvector match_document_chunks function.
        """
        try:
            supabase = await get_supabase_service_client()
            
            response = await supabase.rpc(
                "match_document_chunks",
                {
                    "query_embedding": query_embedding,
                    "match_threshold": threshold,
                    "match_count": top_k
                }
            ).execute()
            
            return response.data
            
        except Exception as e:
            logger.error("vector_search_failed", error=str(e))
            raise ValueError(f"Vector search failed: {str(e)}")
            
    async def insert_chunks(self, document_id: str, chunks_data: List[Dict[str, Any]]):
        """
        Inserts generated chunks and embeddings into the document_chunks table.
        """
        try:
            supabase = await get_supabase_service_client()
            
            # Insert in batches if there are many chunks, but for now we do it all at once
            await supabase.table("document_chunks").insert(chunks_data).execute()
            
        except Exception as e:
            logger.error("chunk_insertion_failed", document_id=document_id, error=str(e))
            raise ValueError(f"Failed to insert chunks: {str(e)}")
            
    async def delete_chunks_by_document(self, document_id: str):
        """
        Deletes all chunks associated with a document (useful for re-indexing).
        """
        try:
            supabase = await get_supabase_service_client()
            await supabase.table("document_chunks").delete().eq("document_id", document_id).execute()
        except Exception as e:
            logger.error("chunk_deletion_failed", document_id=document_id, error=str(e))
            raise ValueError(f"Failed to delete chunks: {str(e)}")
