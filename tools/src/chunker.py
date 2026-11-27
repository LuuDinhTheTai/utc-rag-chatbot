"""Structure-aware text chunker for RAG ingestion.

Properties:
- Validates ``min_chunk_size <= target_chunk_size <= max_chunk_size`` and
  ``0 <= overlap_size < target_chunk_size``.
- Preserves sentence-ending punctuation (Vietnamese, English, CJK).
- Enforces ``max_chunk_size`` as a true hard upper bound (characters, sized to
  stay well within ``embeddinggemma``'s context window).
- Supports configurable character overlap between adjacent chunks.
- Preserves heading context (markdown ``# ...`` or ``[Page N]`` markers) and
  keeps small table blocks intact when they fit within ``max_chunk_size``.
- Never emits empty chunks; ``char_count`` always equals ``len(body)``.
"""

import logging
import re
from typing import Any, Dict, List, Optional

from .exceptions import ContentTooLargeError

logger = logging.getLogger(__name__)

# Split AFTER sentence-ending punctuation followed by whitespace (keeps punctuation!)
_SENTENCE_SPLIT_RE = re.compile(r"(?<=[.!?。！？])\s+")
_HEADING_LINE_RE = re.compile(r"^(#{1,6}\s+.+|\[Page\s+\d+\])$")


class Chunker:
    """Chunk text into bounded, context-preserving segments for embedding."""

    def __init__(
        self,
        target_chunk_size: int = 512,
        min_chunk_size: int = 100,
        max_chunk_size: int = 1024,
        overlap_size: int = 80,
        max_input_chars: int = 5_000_000,
    ):
        if min_chunk_size <= 0:
            raise ValueError("min_chunk_size must be > 0")
        if target_chunk_size < min_chunk_size:
            raise ValueError("target_chunk_size must be >= min_chunk_size")
        if max_chunk_size < target_chunk_size:
            raise ValueError("max_chunk_size must be >= target_chunk_size")
        if overlap_size < 0 or overlap_size >= target_chunk_size:
            raise ValueError("overlap_size must satisfy 0 <= overlap_size < target_chunk_size")
        if max_input_chars <= 0:
            raise ValueError("max_input_chars must be > 0")

        self.target_chunk_size = target_chunk_size
        self.min_chunk_size = min_chunk_size
        self.max_chunk_size = max_chunk_size
        self.overlap_size = overlap_size
        self.max_input_chars = max_input_chars

    def chunk_text(self, text: str) -> List[Dict[str, Any]]:
        """Split ``text`` into ordered chunk dicts (``position``, ``body``, ``char_count``)."""
        if not text or not text.strip():
            return []

        if len(text) > self.max_input_chars:
            raise ContentTooLargeError(
                f"Input text ({len(text)} chars) exceeds max_input_chars ({self.max_input_chars})"
            )

        blocks = self._split_into_blocks(text)
        raw_chunks: List[str] = []
        current_heading: Optional[str] = None
        current_parts: List[str] = []
        current_len = 0

        def flush() -> None:
            nonlocal current_parts, current_len
            if not current_parts:
                return
            body = "\n".join(current_parts).strip()
            if body:
                raw_chunks.append(body)
            current_parts = []
            current_len = 0

        def start_with_context() -> None:
            nonlocal current_parts, current_len
            prefix_parts: List[str] = []
            if current_heading:
                prefix_parts.append(current_heading)
            if self.overlap_size > 0 and raw_chunks:
                tail = self._extract_overlap(raw_chunks[-1], self.overlap_size)
                if tail and (not current_heading or tail != current_heading):
                    prefix_parts.append(tail)
            prefix = "\n".join(prefix_parts).strip()
            # Only prepend context if it leaves meaningful room for new content
            if prefix and len(prefix) <= self.max_chunk_size // 2:
                current_parts = [prefix]
                current_len = len(prefix)

        for block_type, content in blocks:
            if block_type == "heading":
                # Flush previous section if it meets minimum size
                if current_parts and current_len >= self.min_chunk_size:
                    flush()
                current_heading = content
                if not current_parts:
                    current_parts = [content]
                    current_len = len(content)
                elif current_len + 1 + len(content) <= self.max_chunk_size:
                    current_parts.append(content)
                    current_len += 1 + len(content)
                else:
                    flush()
                    current_parts = [content]
                    current_len = len(content)
                continue

            if block_type == "table":
                # Keep whole table intact if it fits within max_chunk_size
                if len(content) <= self.max_chunk_size:
                    if current_parts and current_len + 1 + len(content) > self.max_chunk_size:
                        flush()
                        if current_heading and len(current_heading) + 1 + len(content) <= self.max_chunk_size:
                            current_parts = [current_heading]
                            current_len = len(current_heading)
                    current_parts.append(content)
                    current_len += (1 if len(current_parts) > 1 else 0) + len(content)
                    if current_len >= self.target_chunk_size:
                        flush()
                    continue
                # Oversized table: split row-by-row preserving header row if possible
                units = [line for line in content.splitlines() if line.strip()]
            else:
                units = self._split_sentences(content)

            for unit in units:
                if len(unit) > self.max_chunk_size:
                    flush()
                    for piece in self._split_long_unit(unit):
                        if current_heading and len(current_heading) + 1 + len(piece) <= self.max_chunk_size:
                            raw_chunks.append(f"{current_heading}\n{piece}")
                        else:
                            raw_chunks.append(piece)
                    continue

                sep = 1 if current_parts else 0
                would_exceed_max = current_parts and (current_len + sep + len(unit) > self.max_chunk_size)
                would_exceed_target = (
                    current_parts
                    and current_len >= self.min_chunk_size
                    and (current_len + sep + len(unit) > self.target_chunk_size)
                )

                if would_exceed_max or would_exceed_target:
                    flush()
                    start_with_context()
                    # Ensure context + unit still obeys hard max_chunk_size
                    if current_parts and (current_len + 1 + len(unit) > self.max_chunk_size):
                        current_parts = []
                        current_len = 0

                if current_parts:
                    current_parts.append(unit)
                    current_len += 1 + len(unit)
                else:
                    current_parts = [unit]
                    current_len = len(unit)

        flush()

        chunk_objects: List[Dict[str, Any]] = []
        for body in raw_chunks:
            cleaned = body.strip()
            if not cleaned:
                continue
            # Final safety guarantee: never exceed max_chunk_size
            if len(cleaned) > self.max_chunk_size:
                for sub in self._split_long_unit(cleaned):
                    s = sub.strip()
                    if s:
                        chunk_objects.append({
                            "position": len(chunk_objects),
                            "body": s,
                            "char_count": len(s),
                        })
            else:
                chunk_objects.append({
                    "position": len(chunk_objects),
                    "body": cleaned,
                    "char_count": len(cleaned),
                })

        if chunk_objects:
            logger.debug(
                "Chunked %d chars into %d chunk(s) (avg %d chars/chunk)",
                len(text), len(chunk_objects), len(text) // len(chunk_objects),
            )
        return chunk_objects

    def _split_into_blocks(self, text: str) -> List[tuple[str, str]]:
        """Classify paragraphs into ('heading' | 'table' | 'paragraph', content)."""
        blocks: List[tuple[str, str]] = []
        raw_paragraphs = re.split(r"\n\s*\n+", text.strip())

        for para in raw_paragraphs:
            lines = [ln.strip() for ln in para.splitlines() if ln.strip()]
            if not lines:
                continue

            idx = 0
            while idx < len(lines):
                line = lines[idx]
                if _HEADING_LINE_RE.match(line):
                    blocks.append(("heading", line))
                    idx += 1
                elif " | " in line:
                    table_lines = []
                    while idx < len(lines) and " | " in lines[idx]:
                        table_lines.append(lines[idx])
                        idx += 1
                    blocks.append(("table", "\n".join(table_lines)))
                else:
                    para_lines = []
                    while idx < len(lines) and not _HEADING_LINE_RE.match(lines[idx]) and " | " not in lines[idx]:
                        para_lines.append(lines[idx])
                        idx += 1
                    blocks.append(("paragraph", " ".join(para_lines)))

        return blocks

    def _split_sentences(self, text: str) -> List[str]:
        parts = _SENTENCE_SPLIT_RE.split(text.strip())
        return [s.strip() for s in parts if s.strip()]

    def _split_long_unit(self, unit: str) -> List[str]:
        """Split a unit exceeding ``max_chunk_size`` at word boundaries, or hard-split long words."""
        words = unit.split()
        chunks: List[str] = []
        current: List[str] = []
        current_len = 0

        for word in words:
            if len(word) > self.max_chunk_size:
                if current:
                    chunks.append(" ".join(current))
                    current = []
                    current_len = 0
                for i in range(0, len(word), self.max_chunk_size):
                    chunks.append(word[i : i + self.max_chunk_size])
                continue

            sep = 1 if current else 0
            if current and current_len + sep + len(word) > self.max_chunk_size:
                chunks.append(" ".join(current))
                current = [word]
                current_len = len(word)
            else:
                current.append(word)
                current_len += sep + len(word)

        if current:
            chunks.append(" ".join(current))
        return chunks

    @staticmethod
    def _extract_overlap(previous_chunk: str, overlap_size: int) -> str:
        """Take up to ``overlap_size`` trailing characters aligned to a word boundary."""
        if len(previous_chunk) <= overlap_size:
            return previous_chunk
        tail = previous_chunk[-overlap_size:]
        space_idx = tail.find(" ")
        if 0 < space_idx < len(tail) - 1:
            return tail[space_idx + 1 :].strip()
        return tail.strip()
