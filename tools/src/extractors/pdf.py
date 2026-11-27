"""PDF content extractor.

Guarantees:
- Page count limit (``max_pages``) and character budget (``max_chars``).
- Preserves ``[Page N]`` markers for source provenance.
- Tracks failed pages and empty pages explicitly in metadata so callers can
  detect partial extraction; fails if >50% of pages error out or if all pages fail.
- Handles encrypted PDFs (attempts empty password, otherwise raises ``ParsingError``).
- Distinguishes corrupted PDFs (``ParsingError``) from empty / image-only PDFs
  with no extractable text (``EmptyContentError``).
- No OCR in this release.
"""

import logging
import re
from io import BytesIO
from typing import Any, Dict, List

import pypdf

from ..exceptions import EmptyContentError, ParsingError

logger = logging.getLogger(__name__)


class PDFExtractor:
    """Extract text and metadata from PDF documents."""

    def __init__(self, max_pages: int = 500, max_chars: int = 5_000_000):
        if max_pages < 1:
            raise ValueError("max_pages must be >= 1")
        if max_chars < 1:
            raise ValueError("max_chars must be >= 1")
        self.max_pages = max_pages
        self.max_chars = max_chars

    def extract(self, pdf_bytes: bytes, url: str = "") -> Dict[str, Any]:
        """Extract text and metadata from PDF bytes.

        Returns:
            Dict with keys ``text``, ``title``, ``metadata`` (including
            ``num_pages``, ``extracted_pages``, ``failed_pages``, ``empty_pages``,
            ``truncated``, ``partial_extraction``).
        """
        if not pdf_bytes:
            raise EmptyContentError(f"Empty PDF bytes: {url}")

        try:
            reader = pypdf.PdfReader(BytesIO(pdf_bytes))

            if reader.is_encrypted:
                try:
                    decrypt_result = reader.decrypt("")
                except Exception as e:
                    raise ParsingError(f"PDF is encrypted and could not be decrypted: {url}") from e
                if not decrypt_result:
                    raise ParsingError(f"PDF is encrypted: {url}")

            num_pages = len(reader.pages)
            if num_pages == 0:
                raise EmptyContentError(f"PDF contains 0 pages: {url}")

            title = "Untitled"
            metadata: Dict[str, Any] = {}
            if reader.metadata:
                if reader.metadata.title:
                    title = str(reader.metadata.title).strip() or "Untitled"
                if reader.metadata.author:
                    metadata["author"] = str(reader.metadata.author).strip()
                if reader.metadata.subject:
                    metadata["subject"] = str(reader.metadata.subject).strip()
                if reader.metadata.creator:
                    metadata["creator"] = str(reader.metadata.creator).strip()

            pages_to_process = min(num_pages, self.max_pages)
            truncated = num_pages > self.max_pages
            if truncated:
                logger.warning(
                    "PDF %s has %d pages; truncating to max_pages=%d",
                    url, num_pages, self.max_pages,
                )

            text_parts: List[str] = []
            failed_pages: List[int] = []
            empty_pages: List[int] = []
            total_chars = 0

            for page_num in range(1, pages_to_process + 1):
                page = reader.pages[page_num - 1]
                try:
                    page_text = page.extract_text()
                except Exception as e:
                    failed_pages.append(page_num)
                    logger.warning(
                        "Failed to extract text from page %d/%d of %s: %s",
                        page_num, num_pages, url, e,
                    )
                    continue

                if not page_text or not page_text.strip():
                    empty_pages.append(page_num)
                    continue

                cleaned = self._normalize_whitespace(page_text)
                if not cleaned:
                    empty_pages.append(page_num)
                    continue

                entry = f"[Page {page_num}]\n{cleaned}"
                if total_chars + len(entry) > self.max_chars:
                    truncated = True
                    logger.warning("PDF %s exceeded max_chars=%d at page %d", url, self.max_chars, page_num)
                    break

                text_parts.append(entry)
                total_chars += len(entry)

            # Fail if every attempted page errored, or majority of pages failed to parse
            if failed_pages and not text_parts:
                raise ParsingError(
                    f"Failed to extract text from PDF {url}: {len(failed_pages)}/{pages_to_process} pages failed"
                )
            if failed_pages and (len(failed_pages) / pages_to_process) > 0.5:
                raise ParsingError(
                    f"PDF {url} extraction too incomplete: {len(failed_pages)}/{pages_to_process} pages failed"
                )

            if not text_parts:
                raise EmptyContentError(
                    f"No extractable text in PDF {url} ({num_pages} page(s), possibly scanned/image-only)"
                )

            text = "\n\n".join(text_parts)

            metadata.update({
                "num_pages": num_pages,
                "pages_attempted": pages_to_process,
                "extracted_pages": len(text_parts),
                "failed_pages": failed_pages,
                "empty_pages": empty_pages,
                "truncated": truncated,
                "partial_extraction": bool(failed_pages or truncated),
            })

            logger.debug(
                "Extracted %d chars from PDF %s (%d/%d pages, failed=%d, title=%s)",
                len(text), url, len(text_parts), num_pages, len(failed_pages), title,
            )

            return {
                "text": text,
                "title": title,
                "metadata": metadata,
            }

        except (ParsingError, EmptyContentError):
            raise
        except Exception as e:
            raise ParsingError(f"Failed to parse PDF from {url}: {e}") from e

    def _normalize_whitespace(self, text: str) -> str:
        text = text.replace("\t", " ")
        text = re.sub(r" +", " ", text)
        text = re.sub(r"\n\s*\n\s*\n+", "\n\n", text)
        lines = [line.strip() for line in text.split("\n")]
        return "\n".join(lines).strip()
