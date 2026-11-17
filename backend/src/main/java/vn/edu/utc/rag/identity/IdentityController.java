package vn.edu.utc.rag.identity;

import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.web.csrf.*;
import org.springframework.web.bind.annotation.*;
import vn.edu.utc.rag.security.Access;

@RestController
@RequestMapping("/api/v1")
public class IdentityController {

  private final IdentityService service;

  private final Access access;

  private final CsrfTokenRepository csrf;

  public IdentityController(IdentityService service, Access access, CsrfTokenRepository csrf) {
    this.service = service;
    this.access = access;
    this.csrf = csrf;
  }

  @GetMapping("/auth/csrf")
  Map<String, Object> csrf(CsrfToken token, HttpServletRequest req) {
    access.user(req);
    access.guest(req, true);
    return Map.of("token", token.getToken(), "header_name", token.getHeaderName());
  }

  @PostMapping("/auth/accounts")
  ResponseEntity<?> register(
      @RequestBody Map<String, Object> body, HttpServletRequest req, HttpServletResponse res) {
    return ResponseEntity.status(201).body(service.authenticate(body, true, req, res));
  }

  @PostMapping("/auth/sessions")
  Map<String, Object> login(
      @RequestBody Map<String, Object> body, HttpServletRequest req, HttpServletResponse res) {
    return service.authenticate(body, false, req, res);
  }

  @DeleteMapping("/auth/sessions/current")
  ResponseEntity<?> logout(HttpServletRequest req, HttpServletResponse res) {
    access.requireUser(req);
    csrf.saveToken(null, req, res);
    req.getSession().invalidate();
    var c = new Cookie("UTC_SESSION", "");
    c.setPath("/");
    c.setHttpOnly(true);
    c.setMaxAge(0);
    res.addCookie(c);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/me")
  Map<String, Object> me(HttpServletRequest req) {
    return service.me(req);
  }
}
