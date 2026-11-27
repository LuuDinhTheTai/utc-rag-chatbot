"""HTML content extractor.

Single-pass extraction returning text, title, and metadata together so callers
never parse the same HTML document twice.

Note: OCR is out of scope for this release. Optional ``ocr_extractor`` hooks are
preserved for compatibility when explicitly supplied, but disabled by default.
"""

import logging
import re
from typing import Any, Dict, Optional
from urllib.parse import urljoin

from bs4 import BeautifulSoup, Comment

from ..exceptions import ParsingError

logger = logging.getLogger(__name__)

_META_CHARSET_RE = re.compile(
    rb'<meta[^>]+charset=["\']?\s*([a-zA-Z0-9_\-]+)',
    re.IGNORECASE,
)


def _decode_html_bytes(html_bytes: bytes) -> str:
    """Decode raw HTML bytes honoring BOM, <meta charset>, UTF-8, and fallbacks."""
    # Check UTF-8 BOM
    if html_bytes.startswith(b"\xef\xbb\xbf"):
        return html_bytes.decode("utf-8-sig", errors="replace")

    # Try strict UTF-8 first (covers the vast majority of modern Vietnamese/English pages)
    try:
        return html_bytes.decode("utf-8")
    except UnicodeDecodeError:
        pass

    # Look for declared charset in first 4 KB
    match = _META_CHARSET_RE.search(html_bytes[:4096])
    if match:
        declared = match.group(1).decode("ascii", errors="ignore").strip()
        if declared:
            try:
                return html_bytes.decode(declared, errors="replace")
            except LookupError:
                pass

    # Fallback for legacy Vietnamese / Western pages
    for enc in ("cp1258", "cp1252", "latin-1"):
        try:
            return html_bytes.decode(enc)
        except UnicodeDecodeError:
            continue
    return html_bytes.decode("latin-1", errors="replace")


class HTMLExtractor:
    """Extract clean text and metadata from HTML documents in a single pass."""

    REMOVE_TAGS = {"script", "style", "noscript", "iframe", "object", "embed", "svg", "canvas", "template"}

    def __init__(self, ocr_extractor=None, http_client=None):
        self.ocr_extractor = ocr_extractor
        self.http_client = http_client

    def extract(self, html_bytes: bytes, url: str) -> Dict[str, Any]:
        """Extract text, title, and metadata from HTML bytes.

        Returns:
            Dict with keys: ``text``, ``title``, ``metadata``, ``ocr_text``, ``image_count``.
        """
        if not html_bytes:
            return {
                "text": "",
                "title": "Untitled",
                "metadata": {},
                "ocr_text": "",
                "image_count": 0,
            }

        try:
            html = _decode_html_bytes(html_bytes)
            soup = BeautifulSoup(html, "html.parser")

            # Extract metadata before decomposing tags
            metadata = self._extract_metadata(soup)

            # Extract title handling nested elements (soup.title.string is None when nested)
            title = ""
            if soup.title:
                title = " ".join(soup.title.get_text(" ", strip=True).split())
            if not title and metadata.get("og_title"):
                title = metadata["og_title"]
            if not title:
                h1 = soup.find("h1")
                if h1:
                    title = " ".join(h1.get_text(" ", strip=True).split())

            # Remove noise tags
            for tag in soup(self.REMOVE_TAGS):
                tag.decompose()

            # Remove HTML comments
            for comment in soup.find_all(string=lambda text: isinstance(text, Comment)):
                comment.extract()

            # Optional OCR hook (disabled unless explicitly provided)
            ocr_text = ""
            image_count = 0
            if self.ocr_extractor and self.http_client:
                ocr_text, image_count = self._extract_text_from_images(soup, url)

            body = soup.body if soup.body else soup
            text = body.get_text(separator="\n", strip=True)
            text = self._normalize_whitespace(text)

            if ocr_text:
                text = (text + "\n\n" + ocr_text).strip()

            metadata["ocr_enabled"] = self.ocr_extractor is not None
            metadata["images_processed"] = image_count

            logger.debug(
                "Extracted %d chars from HTML: %s (title: %s)",
                len(text), url, title or "Untitled",
            )

            return {
                "text": text,
                "title": title or "Untitled",
                "metadata": metadata,
                "ocr_text": ocr_text,
                "image_count": image_count,
            }

        except Exception as e:
            raise ParsingError(f"Failed to parse HTML from {url}: {e}") from e

    def _normalize_whitespace(self, text: str) -> str:
        text = text.replace("\t", " ")
        text = re.sub(r" +", " ", text)
        text = re.sub(r"\n\s*\n\s*\n+", "\n\n", text)
        lines = [line.strip() for line in text.split("\n")]
        return "\n".join(lines).strip()

    def _extract_metadata(self, soup: BeautifulSoup) -> Dict[str, Any]:
        metadata: Dict[str, Any] = {}

        meta_desc = soup.find("meta", attrs={"name": re.compile(r"^description$", re.I)})
        if meta_desc and meta_desc.get("content"):
            metadata["description"] = meta_desc["content"].strip()

        meta_keywords = soup.find("meta", attrs={"name": re.compile(r"^keywords$", re.I)})
        if meta_keywords and meta_keywords.get("content"):
            metadata["keywords"] = meta_keywords["content"].strip()

        meta_author = soup.find("meta", attrs={"name": re.compile(r"^author$", re.I)})
        if meta_author and meta_author.get("content"):
            metadata["author"] = meta_author["content"].strip()

        og_title = soup.find("meta", attrs={"property": "og:title"})
        if og_title and og_title.get("content"):
            metadata["og_title"] = og_title["content"].strip()

        return metadata

    def _extract_text_from_images(self, soup: BeautifulSoup, base_url: str) -> tuple[str, int]:
        """Optional OCR helper (only invoked if ``ocr_extractor`` is explicitly configured)."""
        if not self.ocr_extractor or not self.http_client:
            return "", 0

        img_tags = soup.find_all("img")
        if not img_tags:
            return "", 0

        ocr_texts = []
        successful_count = 0

        for idx, img in enumerate(img_tags):
            try:
                img_src = img.get("src")
                if not img_src:
                    continue
                img_url = urljoin(base_url, img_src)
                if img_url.startswith("data:"):
                    continue
                result = self.ocr_extractor.extract_from_url(img_url, self.http_client, min_confidence=0.3)
                if result.text and len(result.text) > 20:
                    ocr_texts.append(f"[Image: {img_url}]\n{result.text}")
                    successful_count += 1
            except Exception as e:
                logger.warning("Failed to OCR image %d on %s: %s", idx + 1, base_url, e)
                continue

        return "\n\n".join(ocr_texts), successful_count
