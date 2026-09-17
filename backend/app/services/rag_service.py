import structlog
from typing import AsyncGenerator
from app.services.embedding_service import EmbeddingService
from app.services.vector_service import VectorService
from app.services.gemini_service import GeminiService
from app.schemas.chat import ChatResponse, ChatSource

logger = structlog.get_logger(__name__)

SYSTEM_INSTRUCTION = """
Bạn là một AI assistant hỗ trợ tư vấn tuyển sinh đại học bằng kiến trúc RAG.
Hãy trả lời câu hỏi của người dùng dựa trên thông tin trong CONTEXT được cung cấp.

Quy tắc:
1. Chỉ sử dụng thông tin có trong CONTEXT khi trả lời.
2. Không tự bịa thông tin.
3. Nếu CONTEXT không có đủ thông tin, hãy nói rằng không tìm thấy thông tin phù hợp trong cơ sở dữ liệu.
4. Không giả định các thông tin không xuất hiện trong CONTEXT.
5. Trả lời rõ ràng, ngắn gọn và chính xác, định dạng bằng Markdown.
6. Trả lời bằng ngôn ngữ của người dùng (Tiếng Việt).
"""

class RAGService:
    def __init__(self):
        self.embedding_service = EmbeddingService()
        self.vector_service = VectorService()
        self.gemini_service = GeminiService()

    async def answer(self, question: str) -> ChatResponse:
        # 1. Embed query
        query_embedding = await self.embedding_service.embed_text(question)
        
        # 2. Search vector DB
        chunks = await self.vector_service.search(query_embedding)
        
        # 3. Build context and sources
        context_parts = []
        sources = []
        
        for i, chunk in enumerate(chunks):
            file_name = chunk.get("file_name", "Unknown Document")
            page = chunk.get("metadata", {}).get("page")
            content = chunk.get("content", "")
            
            context_parts.append(f"[Source {i+1}]\nDocument: {file_name}\nPage: {page}\nContent: {content}\n")
            
            sources.append(ChatSource(
                document_id=chunk.get("document_id"),
                file_name=file_name,
                chunk_id=chunk.get("id"),
                similarity=chunk.get("similarity", 0.0),
                page=page
            ))
            
        context_str = "\n".join(context_parts)
        
        prompt = f"CONTEXT:\n\n{context_str}\n\nEND CONTEXT\n\nQUESTION:\n\n{question}"
        
        # 4. Generate answer
        answer = await self.gemini_service.generate(prompt, system_instruction=SYSTEM_INSTRUCTION)
        
        return ChatResponse(
            answer=answer,
            sources=sources
        )

    async def answer_stream(self, question: str) -> AsyncGenerator[dict, None]:
        # 1. Embed query
        query_embedding = await self.embedding_service.embed_text(question)
        
        # 2. Search vector DB
        chunks = await self.vector_service.search(query_embedding)
        
        # 3. Build context and sources
        context_parts = []
        sources = []
        
        for i, chunk in enumerate(chunks):
            file_name = chunk.get("file_name", "Unknown Document")
            page = chunk.get("metadata", {}).get("page")
            content = chunk.get("content", "")
            
            context_parts.append(f"[Source {i+1}]\nDocument: {file_name}\nPage: {page}\nContent: {content}\n")
            
            sources.append(ChatSource(
                document_id=chunk.get("document_id"),
                file_name=file_name,
                chunk_id=chunk.get("id"),
                similarity=chunk.get("similarity", 0.0),
                page=page
            ).model_dump()) # dump to dict for JSON serialization
            
        context_str = "\n".join(context_parts)
        prompt = f"CONTEXT:\n\n{context_str}\n\nEND CONTEXT\n\nQUESTION:\n\n{question}"
        
        # 4. Yield sources first
        yield {"type": "sources", "sources": sources}
        
        # 5. Stream answer
        async for chunk_text in self.gemini_service.generate_stream(prompt, system_instruction=SYSTEM_INSTRUCTION):
            yield {"type": "token", "content": chunk_text}
            
        yield {"type": "done"}
