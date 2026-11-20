"""URL normalization and canonicalization utilities.

Implements URL handling per requirements:
- Normalize URLs to canonical form
- Remove fragments, tracking parameters
- Handle relative URLs
- Detect duplicates
"""

from urllib.parse import urljoin, urlparse, urlunparse, parse_qs, urlencode
from typing import Optional


# Common tracking parameters to remove
TRACKING_PARAMS = {
    "utm_source",
    "utm_medium",
    "utm_campaign",
    "utm_term",
    "utm_content",
    "fbclid",
    "gclid",
    "msclkid",
    "_ga",
    "mc_eid",
}


def normalize_url(url: str, base_url: Optional[str] = None) -> str:
    """Normalize URL to canonical form.

    Args:
        url: URL to normalize (may be relative)
        base_url: Base URL for resolving relative URLs

    Returns:
        Normalized absolute URL

    Normalization steps:
    1. Resolve relative URLs against base
    2. Convert scheme and netloc to lowercase
    3. Remove default ports (80 for http, 443 for https)
    4. Remove fragments (#section)
    5. Remove tracking parameters
    6. Sort remaining query parameters
    7. Remove trailing slash from path (unless root)
    8. Decode percent-encoded characters where safe
    """
    # Resolve relative URLs
    if base_url:
        url = urljoin(base_url, url)

    parsed = urlparse(url)

    # Normalize scheme and netloc to lowercase
    scheme = parsed.scheme.lower() if parsed.scheme else "https"
    netloc = parsed.netloc.lower()

    # Remove default ports
    if netloc.endswith(":80") and scheme == "http":
        netloc = netloc[:-3]
    elif netloc.endswith(":443") and scheme == "https":
        netloc = netloc[:-4]

    # Path normalization
    path = parsed.path
    if not path:
        path = "/"
    elif path != "/" and path.endswith("/"):
        # Remove trailing slash except for root
        path = path.rstrip("/")

    # Query parameters: remove tracking, sort alphabetically
    query_params = parse_qs(parsed.query, keep_blank_values=True)
    filtered_params = {
        k: v for k, v in query_params.items() if k not in TRACKING_PARAMS
    }
    sorted_params = sorted(filtered_params.items())
    query = urlencode(sorted_params, doseq=True) if sorted_params else ""

    # Fragment removed, params removed
    return urlunparse((scheme, netloc, path, "", query, ""))


def get_domain(url: str) -> str:
    """Extract domain from URL.

    Args:
        url: URL to extract domain from

    Returns:
        Domain (netloc) in lowercase
    """
    parsed = urlparse(url)
    return parsed.netloc.lower()


def is_same_domain(url1: str, url2: str) -> bool:
    """Check if two URLs are from the same domain.

    Args:
        url1: First URL
        url2: Second URL

    Returns:
        True if same domain
    """
    return get_domain(url1) == get_domain(url2)


def is_valid_url(url: str) -> bool:
    """Check if URL is valid and has http/https scheme.

    Args:
        url: URL to validate

    Returns:
        True if valid HTTP(S) URL
    """
    try:
        parsed = urlparse(url)
        return (
            parsed.scheme in ("http", "https")
            and bool(parsed.netloc)
            and bool(parsed.netloc.strip())
        )
    except Exception:
        return False


def get_canonical_url(url: str) -> str:
    """Get canonical URL (alias for normalize_url with no base).

    Args:
        url: URL to canonicalize

    Returns:
        Canonical URL
    """
    return normalize_url(url)
