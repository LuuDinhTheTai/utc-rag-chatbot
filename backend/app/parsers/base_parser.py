from abc import ABC, abstractmethod
from typing import List
from pydantic import BaseModel

class ParsedPage(BaseModel):
    content: str
    page_number: int | None = None

class DocumentParser(ABC):
    @abstractmethod
    async def parse(self, file_bytes: bytes) -> List[ParsedPage]:
        pass
