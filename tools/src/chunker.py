"""Text chunking for RAG system.

Implements chunking strategy:
- Sentence-based chunking to preserve context
- Configurable chunk size
- Sequential position assignment (0-indexed)
- No overlap for simplicity (can be added later if needed)
"""

import logging
import re
from typing import List, Dict, Any

logger = logging.getLogger(__name__)


class Chunker:
    """Chunk text into smaller segments for embedding."""

    # Sentence boundary patterns (Vietnamese and English)
    SENTENCE_BOUNDARIES = re.compile(r"[.!?。！？]+[\s\n]+")

    def __init__(
        self,
        target_chunk_size: int = 512,
        min_chunk_size: int = 100,
        max_chunk_size: int = 1024,
    ):
        """Initialize chunker.

        Args:
            target_chunk_size: Target characters per chunk
            min_chunk_size: Minimum chunk size (avoid tiny chunks)
            max_chunk_size: Maximum chunk size (hard limit)
        """
        self.target_chunk_size = target_chunk_size
        self.min_chunk_size = min_chunk_size
        self.max_chunk_size = max_chunk_size

    def chunk_text(self, text: str) -> List[Dict[str, Any]]:
        """Chunk text into segments.

        Args:
            text: Text to chunk

        Returns:
            List of dicts with keys:
                - position: 0-indexed position
                - body: Chunk text
                - char_count: Character count
        """
        if not text or not text.strip():
            return []

        # Split into sentences
        sentences = self._split_sentences(text)

        if not sentences:
            return []

        # Group sentences into chunks
        chunks = []
        current_chunk = []
        current_size = 0

        for sentence in sentences:
            sentence_size = len(sentence)

            # If single sentence exceeds max, split it forcefully
            if sentence_size > self.max_chunk_size:
                # Flush current chunk if any
                if current_chunk:
                    chunks.append(" ".join(current_chunk))
                    current_chunk = []
                    current_size = 0

                # Split long sentence
                sub_chunks = self._split_long_sentence(sentence)
                chunks.extend(sub_chunks)
                continue

            # Check if adding this sentence would exceed target
            if current_size > 0 and current_size + sentence_size > self.target_chunk_size:
                # Only flush if current chunk meets minimum size
                if current_size >= self.min_chunk_size:
                    chunks.append(" ".join(current_chunk))
                    current_chunk = []
                    current_size = 0

            # Add sentence to current chunk
            current_chunk.append(sentence)
            current_size += sentence_size

        # Add remaining chunk
        if current_chunk:
            chunks.append(" ".join(current_chunk))

        # Create chunk objects with positions
        chunk_objects = []
        for position, chunk_text in enumerate(chunks):
            chunk_objects.append(
                {
                    "position": position,
                    "body": chunk_text.strip(),
                    "char_count": len(chunk_text),
                }
            )

        logger.debug(
            f"Chunked {len(text)} chars into {len(chunk_objects)} chunks "
            f"(avg {len(text) // len(chunk_objects)} chars/chunk)"
        )

        return chunk_objects

    def _split_sentences(self, text: str) -> List[str]:
        """Split text into sentences.

        Args:
            text: Text to split

        Returns:
            List of sentences
        """
        # Split on sentence boundaries
        sentences = self.SENTENCE_BOUNDARIES.split(text)

        # Filter empty and strip
        sentences = [s.strip() for s in sentences if s.strip()]

        return sentences

    def _split_long_sentence(self, sentence: str) -> List[str]:
        """Split a long sentence into smaller chunks at word boundaries.

        Args:
            sentence: Long sentence to split

        Returns:
            List of sub-chunks
        """
        words = sentence.split()
        chunks = []
        current = []
        current_size = 0

        for word in words:
            word_size = len(word) + 1  # +1 for space

            if current_size + word_size > self.max_chunk_size:
                if current:
                    chunks.append(" ".join(current))
                    current = []
                    current_size = 0

            current.append(word)
            current_size += word_size

        if current:
            chunks.append(" ".join(current))

        return chunks
