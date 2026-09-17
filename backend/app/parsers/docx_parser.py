import io
from docx import Document
from typing import List
from .base_parser import DocumentParser, ParsedPage
import asyncio

class DOCXParser(DocumentParser):
    async def parse(self, file_bytes: bytes) -> List[ParsedPage]:
        loop = asyncio.get_event_loop()
        return await loop.run_in_executor(None, self._parse_sync, file_bytes)
        
    def _parse_sync(self, file_bytes: bytes) -> List[ParsedPage]:
        try:
            doc = Document(io.BytesIO(file_bytes))
            full_text = []
            for para in doc.paragraphs:
                if para.text.strip():
                    full_text.append(para.text.strip())
            
            # For DOCX, we treat the whole document as a single "page" since pagination isn't strictly preserved
            return [ParsedPage(content="\n".join(full_text), page_number=1)]
        except Exception as e:
            raise ValueError(f"Failed to parse DOCX: {str(e)}")
