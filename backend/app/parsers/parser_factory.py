from .base_parser import DocumentParser
from .pdf_parser import PDFParser
from .docx_parser import DOCXParser
from .txt_parser import TXTParser
from .md_parser import MarkdownParser

class ParserFactory:
    @staticmethod
    def get_parser(file_type: str, file_name: str) -> DocumentParser:
        file_type = file_type.lower()
        file_name = file_name.lower()
        
        if file_type == "application/pdf" or file_name.endswith(".pdf"):
            return PDFParser()
        elif file_type == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" or file_name.endswith(".docx"):
            return DOCXParser()
        elif file_type == "text/plain" or file_name.endswith(".txt"):
            return TXTParser()
        elif file_type == "text/markdown" or file_name.endswith(".md"):
            return MarkdownParser()
        
        raise ValueError(f"Unsupported file type: {file_type} for file {file_name}")
