from app.core.supabase import get_supabase_service_client
import structlog
import uuid
import mimetypes

logger = structlog.get_logger(__name__)

BUCKET_NAME = "knowledge"

class StorageService:
    async def upload_file(self, file_bytes: bytes, file_name: str, document_id: str) -> str:
        """
        Uploads a file to Supabase Storage under the 'knowledge' bucket.
        Path format: {document_id}/{file_name}
        """
        try:
            supabase = await get_supabase_service_client()
            file_path = f"{document_id}/{file_name}"
            
            # Guess mime type
            content_type, _ = mimetypes.guess_type(file_name)
            if not content_type:
                content_type = "application/octet-stream"
                
            await supabase.storage.from_(BUCKET_NAME).upload(
                path=file_path,
                file=file_bytes,
                file_options={"content-type": content_type}
            )
            
            return file_path
        except Exception as e:
            logger.error("storage_upload_failed", document_id=document_id, file_name=file_name, error=str(e))
            raise ValueError(f"Failed to upload file to storage: {str(e)}")

    async def download_file(self, file_path: str) -> bytes:
        """
        Downloads a file from Supabase Storage.
        """
        try:
            supabase = await get_supabase_service_client()
            response = await supabase.storage.from_(BUCKET_NAME).download(file_path)
            return response
        except Exception as e:
            logger.error("storage_download_failed", file_path=file_path, error=str(e))
            raise ValueError(f"Failed to download file from storage: {str(e)}")

    async def delete_file(self, file_path: str):
        """
        Deletes a file from Supabase Storage.
        """
        try:
            supabase = await get_supabase_service_client()
            await supabase.storage.from_(BUCKET_NAME).remove([file_path])
        except Exception as e:
            logger.error("storage_delete_failed", file_path=file_path, error=str(e))
            raise ValueError(f"Failed to delete file from storage: {str(e)}")
