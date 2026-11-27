"""Security Validator — SSRF protection with DNS resolution and IP validation.

Protects against:
- Non-HTTP(S) schemes (file://, gopher://, ftp://, javascript:, data:, ...)
- Userinfo in URLs (``http://user:pass@host`` / ``http://127.0.0.1@allowed.com``)
- Localhost, private (RFC 1918 / RFC 4193), link-local (169.254.0.0/16, fe80::/10),
  CGNAT (100.64.0.0/10), multicast, unspecified (0.0.0.0, ::), reserved, and
  IPv4-mapped IPv6 addresses (``::ffff:127.0.0.1``)
- Decimal / octal / hex IP obfuscations
- DNS-based SSRF: resolves every A and AAAA record via ``socket.getaddrinfo`` and
  rejects the URL if ANY resolved IP is non-global / disallowed
- DNS rebinding: ``validate_and_resolve`` returns the validated IP list so
  ``HTTPClient`` can connect directly to a validated IP instead of resolving again
- Path traversal patterns
"""

import ipaddress
import logging
import socket
from typing import Callable, List, Optional, Tuple
from urllib.parse import urlparse

from .exceptions import SecurityError

logger = logging.getLogger(__name__)

# Additional non-public networks beyond Python's is_global check
EXTRA_BLOCKED_NETWORKS = (
    ipaddress.ip_network("100.64.0.0/10"),   # Carrier-grade NAT (RFC 6598)
    ipaddress.ip_network("192.0.0.0/24"),    # IETF Protocol Assignments
    ipaddress.ip_network("192.0.2.0/24"),    # TEST-NET-1
    ipaddress.ip_network("198.18.0.0/15"),   # Benchmarking
    ipaddress.ip_network("198.51.100.0/24"), # TEST-NET-2
    ipaddress.ip_network("203.0.113.0/24"),  # TEST-NET-3
    ipaddress.ip_network("240.0.0.0/4"),     # Reserved
    ipaddress.ip_network("255.255.255.255/32"),
)

# Type for injectable DNS resolver (useful for unit tests)
ResolverFunc = Callable[[str, Optional[int]], List[str]]


def default_dns_resolver(hostname: str, port: Optional[int] = None) -> List[str]:
    """Resolve hostname to unique IP strings (both IPv4 and IPv6)."""
    infos = socket.getaddrinfo(hostname, port or 80, type=socket.SOCK_STREAM)
    ips: List[str] = []
    seen = set()
    for _family, _type, _proto, _canon, sockaddr in infos:
        ip_str = sockaddr[0]
        if ip_str not in seen:
            seen.add(ip_str)
            ips.append(ip_str)
    return ips


def is_disallowed_ip(ip_str: str) -> bool:
    """Return True if ``ip_str`` is private, loopback, link-local, multicast,
    unspecified, reserved, or otherwise not globally routable.
    Handles IPv4, IPv6, and IPv4-mapped IPv6 addresses.
    """
    # Strip IPv6 zone index if present (e.g. fe80::1%eth0)
    clean = ip_str.split("%", 1)[0]
    ip = ipaddress.ip_address(clean)

    # Unwrap IPv4-mapped IPv6 (e.g. ::ffff:127.0.0.1 or ::ffff:10.0.0.1)
    if isinstance(ip, ipaddress.IPv6Address) and ip.ipv4_mapped is not None:
        ip = ip.ipv4_mapped

    if (
        ip.is_private
        or ip.is_loopback
        or ip.is_link_local
        or ip.is_multicast
        or ip.is_reserved
        or ip.is_unspecified
        or not ip.is_global
    ):
        return True

    if isinstance(ip, ipaddress.IPv4Address):
        for net in EXTRA_BLOCKED_NETWORKS:
            if ip in net:
                return True

    return False


class SecurityValidator:
    """Validates URLs and resolved IPs against SSRF and URL attacks."""

    def __init__(
        self,
        allowed_schemes: Optional[List[str]] = None,
        allow_private_ips: bool = False,
        resolve_dns: bool = True,
        allow_userinfo: bool = False,
        resolver: Optional[ResolverFunc] = None,
    ):
        self.allowed_schemes = {s.lower() for s in (allowed_schemes or ["http", "https"])}
        self.allow_private_ips = allow_private_ips
        self.resolve_dns = resolve_dns
        self.allow_userinfo = allow_userinfo
        self._resolver = resolver or default_dns_resolver

    def validate_url(self, url: str, context: str = "") -> None:
        """Validate URL syntax, host, and (when enabled) DNS resolution.

        Raises:
            SecurityError: If URL fails any security check.
        """
        self.validate_and_resolve(url, context=context)

    def validate_and_resolve(self, url: str, context: str = "") -> Tuple[str, List[str]]:
        """Validate URL and return ``(hostname, validated_ips)``.

        When ``resolve_dns=False``, ``validated_ips`` is empty for non-IP hostnames.
        When ``resolve_dns=True``, every returned IP has been verified safe; if
        DNS resolution fails, raises ``SecurityError``.
        """
        if not url or not isinstance(url, str):
            raise SecurityError(f"Empty or invalid URL {context}")

        if any(ch in url for ch in ("\r", "\n", "\x00")):
            raise SecurityError(f"Control characters in URL not allowed {context}: {url!r}")

        try:
            parsed = urlparse(url)
            port = parsed.port  # validates numeric port
        except ValueError as e:
            raise SecurityError(f"Malformed URL {context}: {url} ({e})") from e

        scheme = (parsed.scheme or "").lower()
        if scheme not in self.allowed_schemes:
            raise SecurityError(
                f"Invalid URL scheme '{scheme}' {context}: {url}. "
                f"Allowed schemes: {', '.join(sorted(self.allowed_schemes))}"
            )

        if not parsed.netloc or not parsed.hostname:
            raise SecurityError(f"URL missing host {context}: {url}")

        # Reject userinfo (e.g. http://user:pass@host or http://127.0.0.1@allowed.com)
        if (parsed.username is not None or parsed.password is not None or "@" in parsed.netloc) and not self.allow_userinfo:
            raise SecurityError(f"Userinfo in URL not allowed {context}: {url}")

        # Check path traversal before any network lookup
        if self._has_path_traversal(url):
            raise SecurityError(f"Path traversal detected {context}: {url}")

        hostname = parsed.hostname.strip().rstrip(".").lower()
        if not hostname:
            raise SecurityError(f"Empty hostname {context}: {url}")

        # Block localhost / internal special-use names immediately
        if not self.allow_private_ips and (
            hostname in ("localhost", "localhost.localdomain", "ip6-localhost", "ip6-loopback")
            or hostname.endswith(".localhost")
            or hostname.endswith(".local")
            or hostname.endswith(".internal")
        ):
            raise SecurityError(f"Localhost/internal hostname not allowed {context}: {url}")

        # Check literal IP (standard, IPv6, or decimal/hex/octal obfuscation)
        literal_ip = self._try_parse_literal_ip(hostname)
        if literal_ip is not None:
            if not self.allow_private_ips and is_disallowed_ip(literal_ip):
                raise SecurityError(f"Private or disallowed IP address not allowed {context}: {url} ({literal_ip})")
            return hostname, [literal_ip]

        # Convert IDN hostname to ASCII punycode for DNS resolution
        try:
            ascii_host = hostname.encode("idna").decode("ascii")
        except UnicodeError as e:
            raise SecurityError(f"Invalid IDN hostname {context}: {hostname}") from e

        if not self.resolve_dns:
            return ascii_host, []

        # Resolve DNS and validate EVERY returned IP (A and AAAA)
        try:
            resolved_ips = self._resolver(ascii_host, port or (443 if scheme == "https" else 80))
        except socket.gaierror as e:
            raise SecurityError(f"DNS resolution failed for '{ascii_host}' {context}: {e}") from e
        except Exception as e:
            raise SecurityError(f"DNS lookup error for '{ascii_host}' {context}: {e}") from e

        if not resolved_ips:
            raise SecurityError(f"DNS returned no addresses for '{ascii_host}' {context}")

        if not self.allow_private_ips:
            for ip_str in resolved_ips:
                try:
                    if is_disallowed_ip(ip_str):
                        raise SecurityError(
                            f"Hostname '{ascii_host}' resolved to disallowed IP {ip_str} {context}: {url}"
                        )
                except ValueError as e:
                    raise SecurityError(
                        f"Hostname '{ascii_host}' resolved to invalid IP '{ip_str}' {context}"
                    ) from e

        logger.debug("URL passed security validation %s: %s -> %s", context, url, resolved_ips)
        return ascii_host, resolved_ips

    def _try_parse_literal_ip(self, hostname: str) -> Optional[str]:
        """Return canonical IP string if hostname is an IP literal (incl. decimal/hex/octal), else None."""
        clean = hostname.split("%", 1)[0]
        try:
            return str(ipaddress.ip_address(clean))
        except ValueError:
            pass

        # Single integer IPv4 (e.g. 2130706433 -> 127.0.0.1) or hex (0x7f000001)
        try:
            if clean.isdigit():
                val = int(clean, 10)
                if 0 <= val <= 0xFFFFFFFF:
                    return str(ipaddress.IPv4Address(val))
            elif clean.startswith("0x") and len(clean) > 2:
                val = int(clean, 16)
                if 0 <= val <= 0xFFFFFFFF:
                    return str(ipaddress.IPv4Address(val))
        except (ValueError, ipaddress.AddressValueError):
            pass

        # Dotted octal/hex IPv4 (e.g. 0177.0.0.1 or 0x7f.0.0.1)
        parts = clean.split(".")
        if 1 < len(parts) <= 4 and all(p for p in parts):
            try:
                nums = []
                for p in parts:
                    if p.startswith(("0x", "0X")):
                        nums.append(int(p, 16))
                    elif len(p) > 1 and p.startswith("0") and p.isdigit():
                        nums.append(int(p, 8))
                    else:
                        nums.append(int(p, 10))
                if len(nums) == 4 and all(0 <= n <= 255 for n in nums):
                    return str(ipaddress.IPv4Address(bytes(nums)))
            except ValueError:
                pass

        return None

    def _is_private_ip(self, hostname: str) -> bool:
        """Backward-compatible helper: check if literal IP or localhost pattern is disallowed."""
        h = hostname.strip().rstrip(".").lower()
        if h in ("localhost", "localhost.localdomain", "ip6-localhost", "ip6-loopback") or h.endswith(".localhost"):
            return True
        literal = self._try_parse_literal_ip(h)
        if literal is not None:
            return is_disallowed_ip(literal)
        return False

    def _has_path_traversal(self, url: str) -> bool:
        """Check for directory traversal patterns in URL."""
        if not url:
            return False
        traversal_patterns = (
            "../",
            "..\\",
            "%2e%2e/",
            "%2e%2e\\",
            "..%2f",
            "..%5c",
            "%2e%2e%2f",
            "%2e%2e%5c",
        )
        url_lower = url.lower()
        for pattern in traversal_patterns:
            if pattern in url_lower:
                logger.warning("Path traversal pattern detected in URL: %s", url)
                return True
        return False

    def validate_redirect(self, original_url: str, redirect_url: str) -> Tuple[str, List[str]]:
        """Validate redirect destination and return ``(hostname, validated_ips)``."""
        logger.debug("Validating redirect: %s -> %s", original_url, redirect_url)
        result = self.validate_and_resolve(redirect_url, context="(redirect destination)")

        orig_host = (urlparse(original_url).hostname or "").lower()
        redir_host = (urlparse(redirect_url).hostname or "").lower()
        if orig_host != redir_host:
            logger.info("Redirect changes host: %s -> %s", orig_host, redir_host)
        return result
