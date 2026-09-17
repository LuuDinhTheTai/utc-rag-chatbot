from typing import List
from .base_parser import DocumentParser, ParsedPage

class MarkdownParser(DocumentParser):
    async def parse(self, file_bytes: bytes) -> List[ParsedPage]:
        try:
            try:
                text = file_bytes.decode('utf-8')
            except UnicodeDecodeError:
                text = file_bytes.decode('latin-1')
                
            # For a more advanced implementation, we could strip markdown tags here if needed
            # Or use a markdown parser to extract text
            # But for RAG, keeping markdown structure is often beneficial
            return [ParsedPage(content=text.strip(), page_number=1)]
        except Exception as e:
            raise ValueError(f"Failed to parse Markdown: {str(e)}")
