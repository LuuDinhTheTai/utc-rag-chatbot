package vn.edu.utc.rag.administration;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.utc.rag.common.*;
import vn.edu.utc.rag.security.*;

@Service
public class AdminService {

  private final Permissions permissions;

  private final Pages pages;

  public AdminService(
      Permissions permissions,
      Pages pages,
      AdminDataRepository repository,
      vn.edu.utc.rag.persistence.repository.AuditRepository audit) {
    this.permissions = permissions;
    this.pages = pages;
    this.repository = repository;
    this.audit = audit;
  }

  public Object accounts(HttpServletRequest req) {
    permissions.check(req, null, "ADMIN");
    return repository.findPageForAccounts(Map.of(), pages.request(req));
  }

  public Object account(UUID id, HttpServletRequest req) {
    permissions.check(req, null, "ADMIN");
    return repository.findUsersForAccount(Map.of("id", id));
  }

  private void lock() {
    repository.applyUsersForLock(Map.of());
  }

  private void invariant() {
    var n = repository.findUsersForInvariant(Map.of());
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
    repository.findUsersForStatus(Map.of("id", id));
    repository.applyUsersForStatus(Map.of("id", id, "status", status));
    invariant();
    audit.record(actor, "ACCOUNT_STATUS", "users", id, body);
    return repository.findUsersForStatus2(Map.of("id", id));
  }

  public Object roles(UUID id, HttpServletRequest req) {
    permissions.check(req, null, "ADMIN");
    account(id, req);
    var args = new HashMap<String, Object>(Map.of("u", id));
    return repository.findPageForRoles(args, pages.request(req));
  }

  public Map<String, Object> role(long id, HttpServletRequest req) {
    permissions.check(req, null, "ADMIN");
    return repository.findRoleAssignmentsForRole(Map.of("id", id));
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
    repository.findUsersForGrant(Map.of("id", id));
    Long campusId =
        campus == null
            ? null
            : Input.id(repository.findCampusesForGrant(Map.of("code", campus)).get("id"));
    var args = new HashMap<String, Object>();
    args.put("id", id);
    args.put("role", role);
    args.put("campus", campusId);
    var row = repository.findRoleAssignmentsForGrant(args);
    repository.applyUsersForGrant(Map.of("id", id));
    audit.record(actor, "GRANT_ROLE", "users", id, body);
    return row;
  }

  @Transactional
  public void revoke(long id, HttpServletRequest req) {
    UUID actor = permissions.check(req, null, "ADMIN");
    lock();
    var row = repository.findRoleAssignmentsForRevoke(Map.of("id", id));
    repository.applyRoleAssignmentsForRevoke(Map.of("id", id));
    repository.applyUsersForRevoke(Map.of("id", UUID.fromString(row.get("user_id").toString())));
    invariant();
    audit.record(actor, "REVOKE_ROLE", "role_assignments", id, Map.of());
  }

  private final AdminDataRepository repository;

  private final vn.edu.utc.rag.persistence.repository.AuditRepository audit;
}
