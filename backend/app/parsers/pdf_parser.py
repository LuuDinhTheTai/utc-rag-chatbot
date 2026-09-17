import io
from pypdf import PdfReader
from typing import List
from .base_parser import DocumentParser, ParsedPage
import asyncio

class PDFParser(DocumentParser):
    async def parse(self, file_bytes: bytes) -> List[ParsedPage]:
        # Using run_in_executor to avoid blocking the event loop with synchronous PDF parsing
        loop = asyncio.get_event_loop()
        return await loop.run_in_executor(None, self._parse_sync, file_bytes)
        
    def _parse_sync(self, file_bytes: bytes) -> List[ParsedPage]:
        parsed_pages = []
        try:
            reader = PdfReader(io.BytesIO(file_bytes))
            for i, page in enumerate(reader.pages):
                text = page.extract_text()
                if text:
                    parsed_pages.append(ParsedPage(content=text.strip(), page_number=i + 1))
        except Exception as e:
            raise ValueError(f"Failed to parse PDF: {str(e)}")
        return parsed_pages
