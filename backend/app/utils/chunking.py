from typing import List, Dict, Any
from pydantic import BaseModel

class Chunk(BaseModel):
    content: str
    chunk_index: int
    page: int | None
    metadata: Dict[str, Any]

class TextChunker:
    def __init__(self, chunk_size: int, chunk_overlap: int):
        self.chunk_size = chunk_size
        self.chunk_overlap = chunk_overlap

    def chunk(self, text: str, base_metadata: Dict[str, Any] = None, page: int = None) -> List[Chunk]:
        """
        Splits text into chunks using a simple character-level sliding window strategy.
        In a real-world scenario, you might want to use a more advanced text splitter like LangChain's RecursiveCharacterTextSplitter.
        """
        if not text:
            return []

        if base_metadata is None:
            base_metadata = {}

        chunks = []
        chunk_index = 0
        start = 0
        text_length = len(text)

        while start < text_length:
            end = start + self.chunk_size
            
            # If not at the end of the text, try to find a natural break (newline or space)
            if end < text_length:
                # Try to break at a double newline first
                break_point = text.rfind('\n\n', start, end)
                if break_point == -1 or break_point <= start + self.chunk_size // 2:
                    # Then try a single newline
                    break_point = text.rfind('\n', start, end)
                if break_point == -1 or break_point <= start + self.chunk_size // 2:
                    # Finally try a space
                    break_point = text.rfind(' ', start, end)
                
                # If a valid break point was found in the second half of the chunk, use it
                if break_point != -1 and break_point > start + self.chunk_size // 2:
                    end = break_point + 1 # Include the break character

            chunk_content = text[start:end].strip()
            
            if chunk_content:
                chunks.append(Chunk(
                    content=chunk_content,
                    chunk_index=chunk_index,
                    page=page,
                    metadata=base_metadata.copy()
                ))
                chunk_index += 1

            start = end - self.chunk_overlap

            # Ensure we always move forward to avoid infinite loops
            if start <= end - self.chunk_size:
                start = end

        return chunks
