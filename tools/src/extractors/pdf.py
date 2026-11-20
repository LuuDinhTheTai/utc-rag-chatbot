"""PDF content extractor.

Extracts text from PDF documents per AD-007:
- Extract text content (not binary bytes hash)
- Handle multi-page PDFs
- Extract metadata
"""

import logging
from typing import Dict, Any
import pypdf

from ..exceptions import ParsingError

logger = logging.getLogger(__name__)


class PDFExtractor:
    """Extract text content from PDF documents."""

    def extract(self, pdf_bytes: bytes, url: str) -> Dict[str, Any]:
        """Extract text and metadata from PDF.

        Args:
            pdf_bytes: Raw PDF content
            url: Source URL (for logging)

        Returns:
            Dict with keys:
                - text: Extracted plain text
                - title: Document title
                - metadata: Additional metadata (author, subject, etc.)

        Raises:
            ParsingError: If PDF parsing fails
        """
        try:
            # Create PDF reader from bytes
            from io import BytesIO

            pdf_file = BytesIO(pdf_bytes)
            reader = pypdf.PdfReader(pdf_file)

            # Check if PDF is encrypted
            if reader.is_encrypted:
                # Try empty password
                if not reader.decrypt(""):
                    raise ParsingError(f"PDF is encrypted: {url}")

            # Extract metadata
            title = "Untitled"
            metadata = {}

            if reader.metadata:
                if reader.metadata.title:
                    title = reader.metadata.title
                if reader.metadata.author:
                    metadata["author"] = reader.metadata.author
                if reader.metadata.subject:
                    metadata["subject"] = reader.metadata.subject
                if reader.metadata.creator:
                    metadata["creator"] = reader.metadata.creator

            # Extract text from all pages
            text_parts = []
            num_pages = len(reader.pages)

            for page_num, page in enumerate(reader.pages, 1):
                try:
                    page_text = page.extract_text()
                    if page_text and page_text.strip():
                        text_parts.append(page_text)
                except Exception as e:
                    logger.warning(
                        f"Failed to extract text from page {page_num}/{num_pages} of {url}: {e}"
                    )

            if not text_parts:
                raise ParsingError(f"No text extracted from PDF: {url}")

            # Join pages with double newline
            text = "\n\n".join(text_parts)

            # Normalize whitespace
            text = self._normalize_whitespace(text)

            metadata["num_pages"] = num_pages
            metadata["extracted_pages"] = len(text_parts)

            logger.debug(
                f"Extracted {len(text)} chars from PDF: {url} "
                f"({len(text_parts)}/{num_pages} pages, title: {title})"
            )

            return {
                "text": text,
                "title": title,
                "metadata": metadata,
            }

        except ParsingError:
            raise
        except Exception as e:
            raise ParsingError(f"Failed to parse PDF from {url}: {e}") from e

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
