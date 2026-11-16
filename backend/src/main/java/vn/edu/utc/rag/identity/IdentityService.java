package vn.edu.utc.rag.identity;

import jakarta.servlet.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.utc.rag.common.*;
import vn.edu.utc.rag.security.*;

@Service
public class IdentityService {
  private final Db db;
  private final vn.edu.utc.rag.persistence.repository.UsersRepository users;
  private final vn.edu.utc.rag.persistence.repository.RoleAssignmentsRepository roles;
  private final Access access;
  private final PasswordEncoder encoder;
  private final RateLimits limits;
  private final CsrfTokenRepository csrf;
  private final String dummy;

  public IdentityService(
      Db db,
      Access access,
      PasswordEncoder encoder,
      RateLimits limits,
      CsrfTokenRepository csrf,
      vn.edu.utc.rag.persistence.repository.UsersRepository users,
      vn.edu.utc.rag.persistence.repository.RoleAssignmentsRepository roles) {
    this.db = db;
    this.users = users;
    this.roles = roles;
    this.access = access;
    this.encoder = encoder;
    this.limits = limits;
    this.csrf = csrf;
    dummy = encoder.encode(UUID.randomUUID().toString());
  }

  @Transactional
  public Map<String, Object> authenticate(
      Map<String, Object> body, boolean register, HttpServletRequest req, HttpServletResponse res) {
    Input.only(body, "email", "password");
    String email = Input.text(body, "email").strip().toLowerCase(Locale.ROOT),
        password = Input.text(body, "password");
    if (email.length() > 254
        || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")
        || password.length() < 8
        || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
      throw ApiException.invalid("Invalid credentials format.");
    String accountKey = "login:account:" + Access.hash(email),
        ipKey = "login:ip:" + req.getRemoteAddr();
    limits.check(accountKey, 5, Duration.ofMinutes(15), false);
    limits.check(ipKey, 5, Duration.ofMinutes(15), false);
    Map<String, Object> account;
    if (register) {
      limits.hit("register:" + req.getRemoteAddr(), 5, Duration.ofMinutes(15));
      var user = new vn.edu.utc.rag.persistence.entity.UsersEntity();
      user.setEmail(email);
      user.setPasswordHash(encoder.encode(password));
      user.setStatus("ACTIVE");
      user.setAuthEpoch(0);
      user.setCreatedAt(java.time.Instant.now());
      users.saveAndFlush(user);
      var role = new vn.edu.utc.rag.persistence.entity.RoleAssignmentsEntity();
      role.setUserId(user.getId());
      role.setRoleCode("STUDENT");
      role.setAssignedAt(java.time.Instant.now());
      roles.saveAndFlush(role);
      account = Map.of("id", user.getId().toString(), "auth_epoch", user.getAuthEpoch());
    } else {
      var existing = users.findByEmail(email);
      boolean matches =
          encoder.matches(password, existing.map(u -> u.getPasswordHash()).orElse(dummy));
      if (!matches || existing.isEmpty() || !"ACTIVE".equals(existing.get().getStatus())) {
        limits.hit(accountKey, 5, Duration.ofMinutes(15));
        limits.hit(ipKey, 5, Duration.ofMinutes(15));
        throw new ApiException(401, "UNAUTHENTICATED", "Invalid email or password.");
      }
      var user = existing.get();
      account = Map.of("id", user.getId().toString(), "auth_epoch", user.getAuthEpoch());
    }
    var session = req.getSession();
    req.changeSessionId();
    session.setAttribute(
        "account",
        new Access.Account(
            UUID.fromString(account.get("id").toString()),
            ((Number) account.get("auth_epoch")).intValue()));
    csrf.saveToken(null, req, res);
    return me(req);
  }

  public Map<String, Object> me(HttpServletRequest req) {
    UUID id = access.requireUser(req);
    var row = db.one("SELECT id,email,status,created_at FROM users WHERE id=:id", Map.of("id", id));
    row.put(
        "roles",
        db.list(
            "SELECT r.id,r.role_code,c.code AS campus_code FROM role_assignments r LEFT JOIN"
                + " campuses c ON c.id=r.campus_id WHERE r.user_id=:id ORDER BY r.id",
            Map.of("id", id)));
    return row;
  }
}
