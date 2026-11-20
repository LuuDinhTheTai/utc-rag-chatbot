"""DOCX content extractor.

Extracts text from Microsoft Word .docx documents per AD-007:
- Extract text content
- Handle paragraphs and tables
- Extract metadata
"""

import logging
from typing import Dict, Any
from io import BytesIO
import docx

from ..exceptions import ParsingError

logger = logging.getLogger(__name__)


class DOCXExtractor:
    """Extract text content from DOCX documents."""

    def extract(self, docx_bytes: bytes, url: str) -> Dict[str, Any]:
        """Extract text and metadata from DOCX.

        Args:
            docx_bytes: Raw DOCX content
            url: Source URL (for logging)

        Returns:
            Dict with keys:
                - text: Extracted plain text
                - title: Document title
                - metadata: Additional metadata

        Raises:
            ParsingError: If DOCX parsing fails
        """
        try:
            # Create document from bytes
            docx_file = BytesIO(docx_bytes)
            doc = docx.Document(docx_file)

            # Extract title from core properties
            title = "Untitled"
            metadata = {}

            if doc.core_properties.title:
                title = doc.core_properties.title
            if doc.core_properties.author:
                metadata["author"] = doc.core_properties.author
            if doc.core_properties.subject:
                metadata["subject"] = doc.core_properties.subject
            if doc.core_properties.keywords:
                metadata["keywords"] = doc.core_properties.keywords

            # Extract text from paragraphs
            text_parts = []

            for para in doc.paragraphs:
                para_text = para.text.strip()
                if para_text:
                    text_parts.append(para_text)

            # Extract text from tables
            for table in doc.tables:
                for row in table.rows:
                    row_text = []
                    for cell in row.cells:
                        cell_text = cell.text.strip()
                        if cell_text:
                            row_text.append(cell_text)
                    if row_text:
                        text_parts.append(" | ".join(row_text))

            if not text_parts:
                raise ParsingError(f"No text extracted from DOCX: {url}")

            # Join with newlines
            text = "\n".join(text_parts)

            # Normalize whitespace
            text = self._normalize_whitespace(text)

            metadata["num_paragraphs"] = len(doc.paragraphs)
            metadata["num_tables"] = len(doc.tables)

            logger.debug(
                f"Extracted {len(text)} chars from DOCX: {url} "
                f"({len(doc.paragraphs)} paragraphs, {len(doc.tables)} tables, title: {title})"
            )

            return {
                "text": text,
                "title": title,
                "metadata": metadata,
            }

        except ParsingError:
            raise
        except Exception as e:
            raise ParsingError(f"Failed to parse DOCX from {url}: {e}") from e

    def _normalize_whitespace(self, text: str) -> str:
        """Normalize whitespace in text.

        Args:
            text: Text to normalize

        Returns:
            Normalized text
        """
        import re

        # Replace multiple spaces
        text = re.sub(r" +", " ", text)

        # Replace multiple newlines (keep max 2)
        text = re.sub(r"\n\s*\n\s*\n+", "\n\n", text)

        # Remove spaces at line boundaries
        lines = [line.strip() for line in text.split("\n")]
        text = "\n".join(lines)

        return text.strip()
