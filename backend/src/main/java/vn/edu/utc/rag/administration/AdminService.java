package vn.edu.utc.rag.administration;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.utc.rag.common.*;
import vn.edu.utc.rag.security.*;

@Service
public class AdminService {
  private final Db db;
  private final Permissions permissions;
  private final Pages pages;

  public AdminService(Db db, Permissions permissions, Pages pages) {
    this.db = db;
    this.permissions = permissions;
    this.pages = pages;
  }

  public Object accounts(HttpServletRequest req) {
    permissions.check(req, null, "ADMIN");
    var args = new HashMap<String, Object>();
    String sql =
        "SELECT id,email,status,auth_epoch,created_at FROM users WHERE true"
            + Pages.filters(req, args, Map.of("status", "status"));
    if (req.getParameter("q") != null) {
      sql += " AND email ILIKE :q";
      args.put("q", "%" + req.getParameter("q") + "%");
    }
    return pages.page(sql, args, req, "id:uuid:asc");
  }

  public Object account(UUID id, HttpServletRequest req) {
    permissions.check(req, null, "ADMIN");
    return db.one(
        "SELECT id,email,status,auth_epoch,created_at FROM users WHERE id=:id", Map.of("id", id));
  }

  private void lock() {
    db.update("LOCK TABLE users IN SHARE ROW EXCLUSIVE MODE", Map.of());
  }

  private void invariant() {
    var n =
        db.one(
            "SELECT count(*) AS n FROM users u JOIN role_assignments r ON r.user_id=u.id WHERE"
                + " u.status='ACTIVE' AND r.role_code='ADMIN' AND r.campus_id IS NULL",
            Map.of());
    if (Long.parseLong(n.get("n").toString()) == 0)
      throw ApiException.conflict("At least one active global ADMIN must remain.");
  }

  @Transactional
  public Object status(UUID id, Map<String, Object> body, HttpServletRequest req) {
    UUID actor = permissions.check(req, null, "ADMIN");
    Input.only(body, "status");
    String status = Input.text(body, "status");
    if (!Set.of("ACTIVE", "LOCKED").contains(status))
      throw ApiException.invalid("Unknown account status.");
    lock();
    db.one("SELECT id FROM users WHERE id=:id FOR UPDATE", Map.of("id", id));
    db.update(
        "UPDATE users SET status=:status,auth_epoch=auth_epoch+1 WHERE id=:id",
        Map.of("id", id, "status", status));
    invariant();
    db.audit(actor, "ACCOUNT_STATUS", "users", id, body);
    return db.one(
        "SELECT id,email,status,auth_epoch,created_at FROM users WHERE id=:id", Map.of("id", id));
  }

  public Object roles(UUID id, HttpServletRequest req) {
    permissions.check(req, null, "ADMIN");
    account(id, req);
    var args = new HashMap<String, Object>(Map.of("u", id));
    return pages.page(
        "SELECT r.id,r.role_code,c.code AS campus_code,r.assigned_at FROM role_assignments r LEFT"
            + " JOIN campuses c ON c.id=r.campus_id WHERE r.user_id=:u"
            + Pages.filters(req, args, Map.of("campus_code", "c.code")),
        args,
        req,
        "id:bigint:asc");
  }

  public Map<String, Object> role(long id, HttpServletRequest req) {
    permissions.check(req, null, "ADMIN");
    return db.one(
        "SELECT r.id,r.user_id,r.role_code,c.code AS campus_code,r.assigned_at FROM"
            + " role_assignments r LEFT JOIN campuses c ON c.id=r.campus_id WHERE r.id=:id",
        Map.of("id", id));
  }

  @Transactional
  public Object grant(UUID id, Map<String, Object> body, HttpServletRequest req) {
    UUID actor = permissions.check(req, null, "ADMIN");
    Input.only(body, "role_code", "campus_code");
    String role = Input.text(body, "role_code");
    if (!Set.of("STUDENT", "STAFF_EDITOR", "STAFF_REVIEWER", "TECH_OPERATOR", "ADMIN")
        .contains(role)) throw ApiException.invalid("Unknown role.");
    Object campus = body.get("campus_code");
    if (campus != null && Set.of("STUDENT", "TECH_OPERATOR", "ADMIN").contains(role))
      throw ApiException.invalid("This role must be global.");
    lock();
    db.one("SELECT id FROM users WHERE id=:id FOR UPDATE", Map.of("id", id));
    Long campusId =
        campus == null
            ? null
            : Input.id(
                db.one("SELECT id FROM campuses WHERE code=:code", Map.of("code", campus))
                    .get("id"));
    var args = new HashMap<String, Object>();
    args.put("id", id);
    args.put("role", role);
    args.put("campus", campusId);
    var row =
        db.one(
            "INSERT INTO role_assignments(user_id,role_code,campus_id,assigned_at)"
                + " VALUES(:id,:role,:campus,now()) RETURNING id",
            args);
    db.update("UPDATE users SET auth_epoch=auth_epoch+1 WHERE id=:id", Map.of("id", id));
    db.audit(actor, "GRANT_ROLE", "users", id, body);
    return row;
  }

  @Transactional
  public void revoke(long id, HttpServletRequest req) {
    UUID actor = permissions.check(req, null, "ADMIN");
    lock();
    var row = db.one("SELECT * FROM role_assignments WHERE id=:id FOR UPDATE", Map.of("id", id));
    db.update("DELETE FROM role_assignments WHERE id=:id", Map.of("id", id));
    db.update(
        "UPDATE users SET auth_epoch=auth_epoch+1 WHERE id=:id",
        Map.of("id", UUID.fromString(row.get("user_id").toString())));
    invariant();
    db.audit(actor, "REVOKE_ROLE", "role_assignments", id, Map.of());
  }
}
