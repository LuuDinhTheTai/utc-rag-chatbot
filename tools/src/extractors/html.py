"""HTML content extractor.

Extracts clean text from HTML documents per AD-007:
- Strip HTML tags
- Normalize whitespace
- Extract metadata (title)
"""

import logging
import re
from typing import Dict, Any
from bs4 import BeautifulSoup, Comment

from ..exceptions import ParsingError

logger = logging.getLogger(__name__)


class HTMLExtractor:
    """Extract text content from HTML documents."""

    # Tags to completely remove with their content
    REMOVE_TAGS = {"script", "style", "noscript", "iframe", "object", "embed"}

    def extract(self, html_bytes: bytes, url: str) -> Dict[str, Any]:
        """Extract text and metadata from HTML.

        Args:
            html_bytes: Raw HTML content
            url: Source URL (for logging)

        Returns:
            Dict with keys:
                - text: Extracted plain text
                - title: Document title
                - metadata: Additional metadata

        Raises:
            ParsingError: If HTML parsing fails
        """
        try:
            # Try UTF-8 first, fall back to latin-1
            try:
                html = html_bytes.decode("utf-8")
            except UnicodeDecodeError:
                html = html_bytes.decode("latin-1")

            soup = BeautifulSoup(html, "html.parser")

            # Extract title
            title = ""
            if soup.title and soup.title.string:
                title = soup.title.string.strip()

            # Remove unwanted tags
            for tag in soup(self.REMOVE_TAGS):
                tag.decompose()

            # Remove comments
            for comment in soup.find_all(string=lambda text: isinstance(text, Comment)):
                comment.extract()

            # Extract text from body or entire document
            body = soup.body if soup.body else soup
            text = body.get_text(separator="\n", strip=True)

            # Normalize whitespace
            text = self._normalize_whitespace(text)

            # Extract metadata
            metadata = self._extract_metadata(soup)

            logger.debug(
                f"Extracted {len(text)} chars from HTML: {url} (title: {title})"
            )

            return {
                "text": text,
                "title": title or "Untitled",
                "metadata": metadata,
            }

        except Exception as e:
            raise ParsingError(f"Failed to parse HTML from {url}: {e}") from e

    def _normalize_whitespace(self, text: str) -> str:
        """Normalize whitespace in text.

        - Replace multiple spaces with single space
        - Replace multiple newlines with double newline
        - Strip leading/trailing whitespace

        Args:
            text: Text to normalize

        Returns:
            Normalized text
        """
        # Replace tabs with spaces
        text = text.replace("\t", " ")

        # Collapse multiple spaces
        text = re.sub(r" +", " ", text)

        # Collapse multiple newlines (keep max 2 for paragraphs)
        text = re.sub(r"\n\s*\n\s*\n+", "\n\n", text)

        # Remove spaces at start/end of lines
        lines = [line.strip() for line in text.split("\n")]
        text = "\n".join(lines)

        return text.strip()

    def _extract_metadata(self, soup: BeautifulSoup) -> Dict[str, str]:
        """Extract metadata from HTML meta tags.

        Args:
            soup: BeautifulSoup object

        Returns:
            Dict of metadata
        """
        metadata = {}

        # Meta description
        meta_desc = soup.find("meta", attrs={"name": "description"})
        if meta_desc and meta_desc.get("content"):
            metadata["description"] = meta_desc["content"].strip()

        # Meta keywords
        meta_keywords = soup.find("meta", attrs={"name": "keywords"})
        if meta_keywords and meta_keywords.get("content"):
            metadata["keywords"] = meta_keywords["content"].strip()

        # Author
        meta_author = soup.find("meta", attrs={"name": "author"})
        if meta_author and meta_author.get("content"):
            metadata["author"] = meta_author["content"].strip()

        # Open Graph title (fallback)
        og_title = soup.find("meta", property="og:title")
        if og_title and og_title.get("content"):
            metadata["og_title"] = og_title["content"].strip()

        return metadata
