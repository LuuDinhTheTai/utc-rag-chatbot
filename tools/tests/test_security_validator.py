"""
Tests for Security Validator module - SSRF prevention and URL validation.
"""

import pytest
from src.security_validator import SecurityValidator
from src.exceptions import SecurityError


class TestSecurityValidator:
    """Tests for SecurityValidator class."""
    
    def test_init(self):
        """Test SecurityValidator initialization."""
        validator = SecurityValidator(
            allowed_schemes=['http', 'https'],
            allow_private_ips=False
        )
        
        assert 'http' in validator.allowed_schemes
        assert 'https' in validator.allowed_schemes
        assert validator.allow_private_ips is False
    
    def test_validate_url_valid_http(self):
        """Test validation passes for valid HTTP URL."""
        validator = SecurityValidator()
        
        # Should not raise
        validator.validate_url('http://utc.edu.vn/page')
    
    def test_validate_url_valid_https(self):
        """Test validation passes for valid HTTPS URL."""
        validator = SecurityValidator()
        
        # Should not raise
        validator.validate_url('https://utc.edu.vn/page')
    
    def test_validate_url_invalid_scheme(self):
        """Test validation fails for invalid scheme."""
        validator = SecurityValidator(allowed_schemes=['http', 'https'])
        
        with pytest.raises(SecurityError, match="Invalid URL scheme"):
            validator.validate_url('ftp://utc.edu.vn/file')
        
        with pytest.raises(SecurityError, match="Invalid URL scheme"):
            validator.validate_url('javascript:alert(1)')
        
        with pytest.raises(SecurityError, match="Invalid URL scheme"):
            validator.validate_url('data:text/html,<script>alert(1)</script>')
    
    def test_validate_url_missing_host(self):
        """Test validation fails for URL without host."""
        validator = SecurityValidator()
        
        with pytest.raises(SecurityError, match="URL missing host"):
            validator.validate_url('http://')
    
    def test_validate_url_localhost(self):
        """Test validation blocks localhost."""
        validator = SecurityValidator()
        
        with pytest.raises(SecurityError, match="Localhost not allowed"):
            validator.validate_url('http://localhost/admin')
        
        with pytest.raises(SecurityError, match="Localhost not allowed"):
            validator.validate_url('http://127.0.0.1/admin')
        
        with pytest.raises(SecurityError, match="Localhost not allowed"):
            validator.validate_url('http://[::1]/admin')
        
        with pytest.raises(SecurityError, match="Localhost not allowed"):
            validator.validate_url('http://0.0.0.0/admin')
    
    def test_validate_url_private_ip_blocked(self):
        """Test validation blocks private IPs by default."""
        validator = SecurityValidator(allow_private_ips=False)
        
        # RFC 1918 private ranges
        with pytest.raises(SecurityError, match="Private IP address not allowed"):
            validator.validate_url('http://10.0.0.1/admin')
        
        with pytest.raises(SecurityError, match="Private IP address not allowed"):
            validator.validate_url('http://192.168.1.1/admin')
        
        with pytest.raises(SecurityError, match="Private IP address not allowed"):
            validator.validate_url('http://172.16.0.1/admin')
    
    def test_validate_url_private_ip_allowed(self):
        """Test validation allows private IPs when configured."""
        validator = SecurityValidator(allow_private_ips=True)
        
        # Should not raise
        validator.validate_url('http://192.168.1.1/page')
    
    def test_validate_url_path_traversal(self):
        """Test validation blocks path traversal attempts."""
        validator = SecurityValidator()
        
        with pytest.raises(SecurityError, match="Path traversal detected"):
            validator.validate_url('http://utc.edu.vn/../../../etc/passwd')
        
        with pytest.raises(SecurityError, match="Path traversal detected"):
            validator.validate_url('http://utc.edu.vn/page?file=..\\..\\windows\\system32')
        
        with pytest.raises(SecurityError, match="Path traversal detected"):
            validator.validate_url('http://utc.edu.vn/%2e%2e/admin')
    
    def test_validate_url_with_context(self):
        """Test validation includes context in error message."""
        validator = SecurityValidator()
        
        with pytest.raises(SecurityError, match="redirect destination"):
            validator.validate_url('ftp://example.com', context="(redirect destination)")
    
    def test_validate_redirect_valid(self):
        """Test redirect validation passes for valid redirect."""
        validator = SecurityValidator()
        
        # Should not raise
        validator.validate_redirect(
            'https://utc.edu.vn/page1',
            'https://utc.edu.vn/page2'
        )
    
    def test_validate_redirect_invalid_destination(self):
        """Test redirect validation blocks invalid destination."""
        validator = SecurityValidator()
        
        with pytest.raises(SecurityError, match="redirect destination"):
            validator.validate_redirect(
                'https://utc.edu.vn/page',
                'http://localhost/admin'
            )
    
    def test_validate_redirect_cross_domain(self):
        """Test redirect validation logs warning for cross-domain but allows it."""
        validator = SecurityValidator()
        
        # Should not raise (cross-domain redirects allowed for CDN, etc.)
        validator.validate_redirect(
            'https://utc.edu.vn/page',
            'https://cdn.example.com/asset'
        )
    
    def test_private_ip_detection_ipv4(self):
        """Test private IP detection for IPv4."""
        validator = SecurityValidator(allow_private_ips=False)
        
        # Test various private IP ranges
        assert validator._is_private_ip('10.0.0.1') is True
        assert validator._is_private_ip('192.168.1.1') is True
        assert validator._is_private_ip('172.16.0.1') is True
        assert validator._is_private_ip('127.0.0.1') is True
        assert validator._is_private_ip('169.254.1.1') is True
        
        # Public IP should not be private
        assert validator._is_private_ip('8.8.8.8') is False
    
    def test_private_ip_detection_hostname_patterns(self):
        """Test suspicious hostname pattern detection."""
        validator = SecurityValidator(allow_private_ips=False)
        
        # Hostnames with suspicious patterns
        assert validator._is_private_ip('192.168.internal.local') is True
        assert validator._is_private_ip('server-10.company.local') is True
        assert validator._is_private_ip('localhost.localdomain') is True
        
        # Normal hostnames should be allowed
        assert validator._is_private_ip('utc.edu.vn') is False
        assert validator._is_private_ip('www.example.com') is False
    
    def test_path_traversal_detection(self):
        """Test path traversal pattern detection."""
        validator = SecurityValidator()
        
        # Various traversal patterns
        assert validator._has_path_traversal('../etc/passwd') is True
        assert validator._has_path_traversal('..\\windows') is True
        assert validator._has_path_traversal('%2e%2e/admin') is True
        assert validator._has_path_traversal('..%2fadmin') is True
        
        # Normal paths should be allowed
        assert validator._has_path_traversal('/tuyen-sinh/page') is False
        assert validator._has_path_traversal('/dao-tao/program.html') is False
    
    def test_ssrf_bypass_attempts(self):
        """Test various SSRF bypass attempts are blocked."""
        validator = SecurityValidator()
        
        # Localhost bypass attempts
        with pytest.raises(SecurityError):
            validator.validate_url('http://localhost@utc.edu.vn/page')  # May be parsed as localhost
        
        # Private IP bypass (URL encoded)
        with pytest.raises(SecurityError):
            validator.validate_url('http://2130706433/')  # 127.0.0.1 in decimal
    
    def test_allowed_schemes_custom(self):
        """Test custom allowed schemes."""
        validator = SecurityValidator(allowed_schemes=['https'])  # Only HTTPS
        
        # HTTPS should pass
        validator.validate_url('https://utc.edu.vn/page')
        
        # HTTP should fail
        with pytest.raises(SecurityError, match="Invalid URL scheme"):
            validator.validate_url('http://utc.edu.vn/page')
