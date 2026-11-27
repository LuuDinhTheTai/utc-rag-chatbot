"""URL normalization, canonicalization, and safe domain matching utilities.

Normalization rules:
1. Resolve relative URLs against ``base_url`` (if given).
2. Lowercase scheme and hostname; encode IDN hostnames to ASCII punycode.
3. Strip userinfo (never kept in canonical URLs) and remove default ports
   (80 for http, 443 for https); preserve non-default ports and IPv6 brackets.
4. Resolve dot segments (``/./``, ``/../``) via RFC 3986 path normalization.
5. Remove trailing slash from non-root paths (keep ``/`` for root).
6. Remove fragments (``#...``).
7. Remove known tracking query parameters (case-insensitive) and sort remaining
   query parameters deterministically (preserving blank values and duplicates).
"""

from typing import Iterable, List, Optional
from urllib.parse import parse_qsl, urlencode, urljoin, urlparse, urlunparse

TRACKING_PARAMS = frozenset({
    "utm_source",
    "utm_medium",
    "utm_campaign",
    "utm_term",
    "utm_content",
    "utm_id",
    "fbclid",
    "gclid",
    "dclid",
    "msclkid",
    "_ga",
    "_gl",
    "mc_eid",
    "mc_cid",
})


def _to_ascii_hostname(hostname: str) -> str:
    """Normalize hostname to lowercase ASCII (punycode for IDN, strip trailing dot)."""
    host = hostname.strip().rstrip(".").lower()
    if not host:
        return ""
    # IPv6 literal (without brackets)
    if ":" in host:
        return host
    try:
        return host.encode("idna").decode("ascii").lower()
    except UnicodeError:
        return host


def _remove_dot_segments(path: str) -> str:
    """RFC 3986 §5.2.4 remove_dot_segments — resolves '.' and '..' while preserving '//'."""
    output: List[str] = []
    buf = path
    while buf:
        if buf.startswith("../"):
            buf = buf[3:]
        elif buf.startswith("./"):
            buf = buf[2:]
        elif buf.startswith("/./"):
            buf = "/" + buf[3:]
        elif buf == "/.":
            buf = "/"
        elif buf.startswith("/../"):
            buf = "/" + buf[4:]
            if output:
                output.pop()
        elif buf == "/..":
            buf = "/"
            if output:
                output.pop()
        elif buf in (".", ".."):
            buf = ""
        else:
            if buf.startswith("/"):
                next_slash = buf.find("/", 1)
            else:
                next_slash = buf.find("/")
            if next_slash == -1:
                output.append(buf)
                buf = ""
            else:
                output.append(buf[:next_slash])
                buf = buf[next_slash:]
    return "".join(output)


def _normalize_path(path: str) -> str:
    """Resolve dot segments (preserving repeated '//') and strip trailing slash (except root)."""
    if not path:
        return "/"
    normalized = _remove_dot_segments(path)
    if not normalized:
        return "/"
    if not normalized.startswith("/"):
        normalized = "/" + normalized
    # Strip a single trailing slash on non-root paths without collapsing internal '//'
    if len(normalized) > 1 and normalized.endswith("/") and not normalized.endswith("//"):
        normalized = normalized[:-1]
    return normalized or "/"


def _format_netloc(scheme: str, hostname: str, port: Optional[int]) -> str:
    host_part = f"[{hostname}]" if ":" in hostname else hostname
    if port is None:
        return host_part
    if (scheme == "http" and port == 80) or (scheme == "https" and port == 443):
        return host_part
    return f"{host_part}:{port}"


def normalize_url(url: str, base_url: Optional[str] = None) -> str:
    """Normalize a URL to its stable canonical form.

    Raises:
        ValueError: If the URL is empty or has an invalid port.
    """
    if not url or not url.strip():
        raise ValueError("URL cannot be empty")
    raw = url.strip()
    if base_url:
        raw = urljoin(base_url, raw)

    parsed = urlparse(raw)
    scheme = (parsed.scheme or "https").lower()

    raw_host = parsed.hostname
    if not raw_host:
        raise ValueError(f"URL missing hostname: {url}")

    hostname = _to_ascii_hostname(raw_host)
    port = parsed.port  # raises ValueError on invalid port
    netloc = _format_netloc(scheme, hostname, port)

    path = _normalize_path(parsed.path)

    # Filter tracking params (case-insensitive) and sort deterministically
    query_pairs = parse_qsl(parsed.query, keep_blank_values=True)
    filtered = [(k, v) for k, v in query_pairs if k.lower() not in TRACKING_PARAMS]
    filtered.sort(key=lambda kv: (kv[0], kv[1]))
    query = urlencode(filtered, doseq=True) if filtered else ""

    return urlunparse((scheme, netloc, path, "", query, ""))


def get_hostname(url: str) -> str:
    """Extract normalized lowercase ASCII hostname (no userinfo, no port, no brackets)."""
    try:
        parsed = urlparse(url)
        if not parsed.hostname:
            return ""
        return _to_ascii_hostname(parsed.hostname)
    except ValueError:
        return ""


def get_domain(url: str) -> str:
    """Alias for ``get_hostname`` (returns lowercase hostname without port/userinfo)."""
    return get_hostname(url)


def is_same_domain(url1: str, url2: str, include_port: bool = False) -> bool:
    """Check whether two URLs belong to the exact same hostname (and optionally port)."""
    h1 = get_hostname(url1)
    h2 = get_hostname(url2)
    if not h1 or not h2 or h1 != h2:
        return False
    if not include_port:
        return True
    p1 = urlparse(url1)
    p2 = urlparse(url2)
    port1 = p1.port or (443 if p1.scheme.lower() == "https" else 80)
    port2 = p2.port or (443 if p2.scheme.lower() == "https" else 80)
    return port1 == port2


def is_domain_allowed(hostname: str, allowed_domains: Iterable[str]) -> bool:
    """Return True if ``hostname`` equals or is a proper subdomain of any allowed domain.

    Prevents suffix confusion such as ``example.com.attacker.com`` matching ``example.com``
    or ``notexample.com`` matching ``example.com``.
    """
    host = _to_ascii_hostname(hostname)
    if not host:
        return False
    for entry in allowed_domains:
        # Allow entries passed as either bare hostnames or URLs/netlocs
        candidate = entry.strip()
        if "://" in candidate:
            allowed = get_hostname(candidate)
        else:
            # Strip port or userinfo if accidentally passed
            if "@" in candidate:
                candidate = candidate.rsplit("@", 1)[1]
            if candidate.startswith("[") and "]" in candidate:
                candidate = candidate[1:candidate.index("]")]
            elif ":" in candidate:
                candidate = candidate.split(":", 1)[0]
            allowed = _to_ascii_hostname(candidate)
        if not allowed:
            continue
        if host == allowed or host.endswith("." + allowed):
            return True
    return False


def is_valid_url(url: str) -> bool:
    """Check if URL is a syntactically valid http/https URL with a hostname."""
    if not url or not isinstance(url, str):
        return False
    try:
        parsed = urlparse(url.strip())
        if parsed.scheme.lower() not in ("http", "https"):
            return False
        if not parsed.hostname:
            return False
        _ = parsed.port  # validates port range 0..65535
        return True
    except ValueError:
        return False


def get_canonical_url(url: str) -> str:
    """Get canonical URL (alias for ``normalize_url`` with no base)."""
    return normalize_url(url)
