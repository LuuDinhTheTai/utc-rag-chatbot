"""
Security Validator - Prevents SSRF and validates URLs for security.

Implements:
- Private IP detection
- Localhost detection
- Redirect validation
- URL scheme validation
- Path traversal detection
"""

import logging
import ipaddress
from typing import Optional
from urllib.parse import urlparse

from .exceptions import SecurityError


logger = logging.getLogger(__name__)


# Private IP ranges (RFC 1918, RFC 4193, etc.)
PRIVATE_IP_RANGES = [
    ipaddress.ip_network('10.0.0.0/8'),
    ipaddress.ip_network('172.16.0.0/12'),
    ipaddress.ip_network('192.168.0.0/16'),
    ipaddress.ip_network('127.0.0.0/8'),  # Localhost
    ipaddress.ip_network('169.254.0.0/16'),  # Link-local
    ipaddress.ip_network('::1/128'),  # IPv6 localhost
    ipaddress.ip_network('fc00::/7'),  # IPv6 private
    ipaddress.ip_network('fe80::/10'),  # IPv6 link-local
]


class SecurityValidator:
    """Validates URLs for security issues."""
    
    def __init__(
        self,
        allowed_schemes: Optional[list] = None,
        allow_private_ips: bool = False,
    ):
        """
        Initialize security validator.
        
        Args:
            allowed_schemes: List of allowed URL schemes (default: ['http', 'https'])
            allow_private_ips: Whether to allow private/internal IPs (default: False)
        """
        self.allowed_schemes = set(allowed_schemes or ['http', 'https'])
        self.allow_private_ips = allow_private_ips
    
    def validate_url(self, url: str, context: str = "") -> None:
        """
        Validate URL for security issues.
        
        Args:
            url: URL to validate
            context: Context string for error messages (e.g., "redirect destination")
            
        Raises:
            SecurityError: If URL fails security validation
        """
        parsed = urlparse(url)
        
        # Check scheme
        if parsed.scheme not in self.allowed_schemes:
            raise SecurityError(
                f"Invalid URL scheme '{parsed.scheme}' {context}: {url}. "
                f"Allowed schemes: {', '.join(self.allowed_schemes)}"
            )
        
        # Check for empty host
        if not parsed.netloc:
            raise SecurityError(f"URL missing host {context}: {url}")
        
        # Extract hostname (remove port and userinfo if present)
        # SECURITY: Check userinfo first - some parsers/clients may handle it differently
        netloc = parsed.netloc
        userinfo = None
        if '@' in netloc:
            # Split userinfo from hostname
            userinfo, netloc = netloc.rsplit('@', 1)
            
            # Check if userinfo contains localhost or private IP patterns
            # This prevents bypass attempts like "localhost@allowed.com"
            if userinfo.lower() in ('localhost', '127.0.0.1', '::1', '0.0.0.0'):
                raise SecurityError(f"Localhost in userinfo not allowed {context}: {url}")
            
            # Also check for private IP patterns in userinfo
            userinfo_lower = userinfo.lower()
            if any(pattern in userinfo_lower for pattern in ['127.', '192.168.', '10.', '172.16.', '172.17.', '172.18.', '172.19.', '172.20.', '172.21.', '172.22.', '172.23.', '172.24.', '172.25.', '172.26.', '172.27.', '172.28.', '172.29.', '172.30.', '172.31.']):
                raise SecurityError(f"Private IP pattern in userinfo not allowed {context}: {url}")
        
        # Handle IPv6 addresses with brackets first
        if netloc.startswith('['):
            # IPv6 address: [::1] or [::1]:8080
            if ']:' in netloc:
                # Has port: [::1]:8080
                hostname = netloc.split(']:')[0][1:]  # Remove [ and extract up to ]
            else:
                # No port: [::1]
                hostname = netloc[1:-1]  # Remove [ and ]
        else:
            # IPv4 or hostname: remove port if present
            hostname = netloc.split(':')[0]
        
        hostname = hostname.lower()
        
        # Check for localhost aliases
        if hostname in ('localhost', '127.0.0.1', '::1', '0.0.0.0', '::'):
            raise SecurityError(f"Localhost not allowed {context}: {url}")
        
        # Check for private IPs
        if not self.allow_private_ips:
            if self._is_private_ip(hostname):
                raise SecurityError(f"Private IP address not allowed {context}: {url}")
        
        # Check for path traversal attempts (including query and fragment)
        if self._has_path_traversal(url):
            raise SecurityError(f"Path traversal detected {context}: {url}")
        
        logger.debug(f"URL passed security validation {context}: {url}")
    
    def _is_private_ip(self, hostname: str) -> bool:
        """
        Check if hostname resolves to a private IP address.
        
        Args:
            hostname: Hostname or IP address string
            
        Returns:
            True if hostname is or resolves to a private IP
        """
        try:
            # Try to parse as IP address directly
            ip = ipaddress.ip_address(hostname)
            
            # Check against private ranges
            for private_range in PRIVATE_IP_RANGES:
                if ip in private_range:
                    logger.warning(f"Private IP detected: {hostname} ({ip})")
                    return True
            
            return False
            
        except ValueError:
            # Not a standard IP address string
            # Try parsing as decimal/octal/hex notation
            try:
                # Check if it's a decimal IP (e.g., 2130706433 = 127.0.0.1)
                if hostname.isdigit():
                    decimal_ip = int(hostname)
                    # Convert to IPv4 address (valid range: 0 to 2^32-1)
                    if 0 <= decimal_ip <= 0xFFFFFFFF:
                        ip = ipaddress.IPv4Address(decimal_ip)
                        for private_range in PRIVATE_IP_RANGES:
                            if ip in private_range:
                                logger.warning(f"Private IP detected (decimal notation): {hostname} ({ip})")
                                return True
            except (ValueError, ipaddress.AddressValueError):
                pass
            
            # Not an IP address, might be a hostname
            # We can't resolve hostnames here without actual DNS lookup
            # which could cause SSRF. So we only check if it looks suspicious.
            
            # Check for IP-like patterns that might bypass filters
            suspicious_patterns = [
                'localhost',
                '127.',
                '192.168.',
                '10.',
                '172.16.',
                '172.17.',
                '172.18.',
                '172.19.',
                '172.20.',
                '172.21.',
                '172.22.',
                '172.23.',
                '172.24.',
                '172.25.',
                '172.26.',
                '172.27.',
                '172.28.',
                '172.29.',
                '172.30.',
                '172.31.',
            ]
            
            hostname_lower = hostname.lower()
            for pattern in suspicious_patterns:
                if pattern in hostname_lower:
                    logger.warning(f"Suspicious hostname pattern detected: {hostname}")
                    return True
            
            return False
    
    def _has_path_traversal(self, url: str) -> bool:
        """
        Check for path traversal attempts in URL.
        
        Args:
            url: Full URL to check
            
        Returns:
            True if URL contains traversal patterns
        """
        if not url:
            return False
        
        # Check for directory traversal patterns
        traversal_patterns = [
            '../',
            '..\\',
            '%2e%2e/',
            '%2e%2e\\',
            '..%2f',
            '..%5c',
        ]
        
        url_lower = url.lower()
        for pattern in traversal_patterns:
            if pattern in url_lower:
                logger.warning(f"Path traversal pattern detected in URL: {url}")
                return True
        
        return False
    
    def validate_redirect(self, original_url: str, redirect_url: str) -> None:
        """
        Validate that redirect destination is safe.
        
        Args:
            original_url: Original URL that initiated the request
            redirect_url: Redirect destination URL
            
        Raises:
            SecurityError: If redirect fails security validation
        """
        logger.info(f"Validating redirect: {original_url} -> {redirect_url}")
        
        # Validate redirect destination with same security rules
        self.validate_url(redirect_url, context="(redirect destination)")
        
        # Additional check: warn if redirect changes domain
        original_domain = urlparse(original_url).netloc.lower()
        redirect_domain = urlparse(redirect_url).netloc.lower()
        
        if original_domain != redirect_domain:
            logger.warning(
                f"Redirect changes domain: {original_domain} -> {redirect_domain}"
            )
            # Note: We allow cross-domain redirects for legitimate cases (e.g., CDN)
            # but log them for monitoring

