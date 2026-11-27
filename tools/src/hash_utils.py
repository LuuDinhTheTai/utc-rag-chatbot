"""Content hashing utilities.

Implements hash strategy per AD-007:
- Hash extracted TEXT content, not raw bytes
- SHA-256 algorithm
- Hex format for database storage
"""

import hashlib


def compute_content_hash(text: str) -> str:
    """Compute SHA-256 hash of text content.

    Per AD-007, we hash extracted text rather than raw bytes.
    This ensures metadata changes don't trigger re-embedding.

    Args:
        text: Extracted text content (already normalized)

    Returns:
        SHA-256 hash in hexadecimal format
    """
    # Encode to UTF-8 for consistent hashing
    text_bytes = text.encode("utf-8")

    # Compute SHA-256 hash
    hash_obj = hashlib.sha256(text_bytes)

    # Return hex digest
    return hash_obj.hexdigest()


def verify_hash(text: str, expected_hash: str) -> bool:
    """Verify text matches expected hash.

    Args:
        text: Text content to verify
        expected_hash: Expected SHA-256 hash (hex)

    Returns:
        True if hash matches
    """
    actual_hash = compute_content_hash(text)
    return actual_hash == expected_hash
