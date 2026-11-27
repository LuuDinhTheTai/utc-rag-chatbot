"""DOCX content extractor.

Guarantees:
- Rejects legacy ``.doc`` (OLE2 binary) files explicitly with
  ``UnsupportedContentTypeError`` instead of passing them to ``python-docx``.
- ZIP bomb protection: inspects archive entry count and total uncompressed size
  before opening the document.
- Preserves original document body order of paragraphs AND tables by iterating
  ``doc.element.body`` children directly.
- Preserves heading hierarchy (``#``..``######``) for downstream chunk context.
- Deduplicates merged table cells (horizontal and vertical) via underlying XML
  element identity (``cell._tc``) and extracts nested tables recursively.
- Extracts unique non-empty header/footer text into metadata.
"""

import logging
import re
import zipfile
from io import BytesIO
from typing import Any, Dict, List, Set

import docx
from docx.oxml.table import CT_Tbl
from docx.oxml.text.paragraph import CT_P
from docx.table import Table, _Cell
from docx.text.paragraph import Paragraph

from ..exceptions import EmptyContentError, ParsingError, UnsupportedContentTypeError

logger = logging.getLogger(__name__)

# Legacy Microsoft Word (.doc) OLE2 Compound Document magic bytes
OLE2_MAGIC = b"\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1"
ZIP_MAGIC = b"PK"

_HEADING_RE = re.compile(r"^Heading\s+([1-6])$", re.IGNORECASE)


class DOCXExtractor:
    """Extract structured text and metadata from .docx documents."""

    def __init__(
        self,
        max_uncompressed_bytes: int = 100 * 1024 * 1024,
        max_zip_entries: int = 1000,
    ):
        self.max_uncompressed_bytes = max_uncompressed_bytes
        self.max_zip_entries = max_zip_entries

    def _validate_archive(self, docx_bytes: bytes, url: str) -> None:
        if docx_bytes.startswith(OLE2_MAGIC):
            raise UnsupportedContentTypeError(
                f"Legacy .doc (OLE2) format is not supported; only .docx is supported ({url})"
            )
        if not docx_bytes.startswith(ZIP_MAGIC):
            raise ParsingError(f"Invalid DOCX file (not a ZIP archive): {url}")

        try:
            with zipfile.ZipFile(BytesIO(docx_bytes)) as zf:
                infos = zf.infolist()
                if len(infos) > self.max_zip_entries:
                    raise ParsingError(
                        f"DOCX archive has too many entries ({len(infos)} > {self.max_zip_entries}): {url}"
                    )
                total_uncompressed = 0
                for info in infos:
                    total_uncompressed += info.file_size
                    if total_uncompressed > self.max_uncompressed_bytes:
                        raise ParsingError(
                            f"DOCX uncompressed size exceeds limit ({self.max_uncompressed_bytes} bytes): {url}"
                        )
        except (ParsingError, UnsupportedContentTypeError):
            raise
        except zipfile.BadZipFile as e:
            raise ParsingError(f"Corrupted DOCX archive: {url} ({e})") from e

    def _format_paragraph(self, para: Paragraph) -> str:
        text = para.text.strip()
        if not text:
            return ""
        style_name = (para.style.name if para.style and para.style.name else "").strip()
        match = _HEADING_RE.match(style_name)
        if match:
            level = int(match.group(1))
            return f"{'#' * level} {text}"
        return text

    def _extract_cell_text(self, cell: _Cell) -> str:
        """Extract text from a table cell including any nested tables."""
        parts: List[str] = []
        for child in cell._tc:
            if isinstance(child, CT_P):
                p_text = Paragraph(child, cell).text.strip()
                if p_text:
                    parts.append(p_text)
            elif isinstance(child, CT_Tbl):
                nested = self._extract_table_lines(Table(child, cell))
                if nested:
                    parts.append("; ".join(nested))
        return " ".join(parts).strip()

    def _extract_table_lines(self, table: Table) -> List[str]:
        """Extract table rows in order, deduplicating merged cells via XML element identity."""
        lines: List[str] = []
        seen_tcs: Set[int] = set()

        for row in table.rows:
            row_cells: List[str] = []
            row_seen: Set[int] = set()
            for cell in row.cells:
                tc_id = id(cell._tc)
                if tc_id in row_seen or tc_id in seen_tcs:
                    continue
                row_seen.add(tc_id)
                seen_tcs.add(tc_id)
                cell_text = self._extract_cell_text(cell)
                if cell_text:
                    row_cells.append(cell_text)
            if row_cells:
                lines.append(" | ".join(row_cells))
        return lines

    def _extract_headers_footers(self, doc: docx.Document) -> Dict[str, List[str]]:
        headers: List[str] = []
        footers: List[str] = []
        seen_h: Set[str] = set()
        seen_f: Set[str] = set()

        for section in doc.sections:
            for para in section.header.paragraphs:
                t = para.text.strip()
                if t and t not in seen_h:
                    seen_h.add(t)
                    headers.append(t)
            for para in section.footer.paragraphs:
                t = para.text.strip()
                if t and t not in seen_f:
                    seen_f.add(t)
                    footers.append(t)
        return {"headers": headers, "footers": footers}

    def extract(self, docx_bytes: bytes, url: str = "") -> Dict[str, Any]:
        """Extract ordered text and metadata from DOCX bytes."""
        if not docx_bytes:
            raise EmptyContentError(f"Empty DOCX bytes: {url}")

        self._validate_archive(docx_bytes, url)

        try:
            doc = docx.Document(BytesIO(docx_bytes))

            title = "Untitled"
            metadata: Dict[str, Any] = {}
            if doc.core_properties.title:
                title = doc.core_properties.title.strip() or "Untitled"
            if doc.core_properties.author:
                metadata["author"] = doc.core_properties.author.strip()
            if doc.core_properties.subject:
                metadata["subject"] = doc.core_properties.subject.strip()
            if doc.core_properties.keywords:
                metadata["keywords"] = doc.core_properties.keywords.strip()

            blocks: List[str] = []
            num_paragraphs = 0
            num_tables = 0

            # Iterate body elements in exact document order
            for child in doc.element.body:
                if isinstance(child, CT_P):
                    num_paragraphs += 1
                    formatted = self._format_paragraph(Paragraph(child, doc))
                    if formatted:
                        blocks.append(formatted)
                elif isinstance(child, CT_Tbl):
                    num_tables += 1
                    table_lines = self._extract_table_lines(Table(child, doc))
                    if table_lines:
                        blocks.append("\n".join(table_lines))

            hf = self._extract_headers_footers(doc)
            if hf["headers"]:
                metadata["headers"] = hf["headers"]
            if hf["footers"]:
                metadata["footers"] = hf["footers"]

            if not blocks:
                raise EmptyContentError(f"No text extracted from DOCX: {url}")

            text = self._normalize_whitespace("\n\n".join(blocks))
            if not text:
                raise EmptyContentError(f"No text extracted from DOCX: {url}")

            metadata["num_paragraphs"] = num_paragraphs
            metadata["num_tables"] = num_tables

            logger.debug(
                "Extracted %d chars from DOCX %s (%d paragraphs, %d tables, title=%s)",
                len(text), url, num_paragraphs, num_tables, title,
            )

            return {
                "text": text,
                "title": title,
                "metadata": metadata,
            }

        except (ParsingError, EmptyContentError, UnsupportedContentTypeError):
            raise
        except Exception as e:
            raise ParsingError(f"Failed to parse DOCX from {url}: {e}") from e

    def _normalize_whitespace(self, text: str) -> str:
        text = text.replace("\t", " ")
        text = re.sub(r" +", " ", text)
        text = re.sub(r"\n\s*\n\s*\n+", "\n\n", text)
        lines = [line.strip() for line in text.split("\n")]
        return "\n".join(lines).strip()
