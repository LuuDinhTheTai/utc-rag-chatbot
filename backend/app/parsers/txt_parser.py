from typing import List
from .base_parser import DocumentParser, ParsedPage

class TXTParser(DocumentParser):
    async def parse(self, file_bytes: bytes) -> List[ParsedPage]:
        try:
            # Try to decode as UTF-8 first, fallback to latin-1
            try:
                text = file_bytes.decode('utf-8')
            except UnicodeDecodeError:
                text = file_bytes.decode('latin-1')
                
            return [ParsedPage(content=text.strip(), page_number=1)]
        except Exception as e:
            raise ValueError(f"Failed to parse TXT: {str(e)}")
