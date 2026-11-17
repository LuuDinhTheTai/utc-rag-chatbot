package vn.edu.utc.rag.service.administration;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.utc.rag.dto.administration.AccountResponse;
import vn.edu.utc.rag.dto.administration.RoleResponse;
import vn.edu.utc.rag.dto.common.IdResponse;
import vn.edu.utc.rag.exception.ApiException;
import vn.edu.utc.rag.mapper.administration.AccountMapper;
import vn.edu.utc.rag.persistence.entity.*;
import vn.edu.utc.rag.persistence.repository.*;
import vn.edu.utc.rag.persistence.repository.query.AdminQueryRepository;
import vn.edu.utc.rag.security.Permissions;
import vn.edu.utc.rag.service.audit.AuditService;
import vn.edu.utc.rag.validation.Input;
import vn.edu.utc.rag.web.pagination.PageRequestFactory;

@Service
@Transactional
public class AdminService {
  private final Permissions permissions;
  private final PageRequestFactory pages;
  private final AdminQueryRepository queries;
  private final AuditService audit;
  private final UsersRepository users;
  private final RoleAssignmentsRepository assignments;
  private final CampusesRepository campuses;
  private final AccountMapper mapper;

  public AdminService(
      Permissions permissions,
      PageRequestFactory pages,
      AdminQueryRepository queries,
      AuditService audit,
      UsersRepository users,
      RoleAssignmentsRepository assignments,
      CampusesRepository campuses,
      AccountMapper mapper) {
    this.permissions = permissions;
    this.pages = pages;
    this.queries = queries;
    this.audit = audit;
    this.users = users;
    this.assignments = assignments;
    this.campuses = campuses;
    this.mapper = mapper;
  }

  public Object accounts(HttpServletRequest req) {
    permissions.check(req, null, "ADMIN");
    return queries.findPageForAccounts(Map.of(), pages.request(req));
  }

  public AccountResponse account(UUID id, HttpServletRequest req) {
    permissions.check(req, null, "ADMIN");
    return mapper.account(users.findById(id).orElseThrow(ApiException::missing));
  }

  private void lock() {
    queries.applyUsersForLock(Map.of());
  }

  private UsersEntity lockedUser(UUID id) {
    return users.findLockedById(id).stream().findFirst().orElseThrow(ApiException::missing);
  }

  private void invariant() {
    users.flush();
    if (users.countActiveAdministrators() == 0)
      throw ApiException.conflict("At least one active global ADMIN must remain.");
  }

  public AccountResponse status(UUID id, Map<String, Object> body, HttpServletRequest req) {
    UUID actor = permissions.check(req, null, "ADMIN");
    Input.only(body, "status");
    String status = Input.text(body, "status");
    if (!Set.of("ACTIVE", "LOCKED").contains(status))
      throw ApiException.invalid("Unknown account status.");
    lock();
    var user = lockedUser(id);
    user.setStatus(status);
    user.setAuthEpoch(user.getAuthEpoch() + 1);
    invariant();
    audit.record(actor, "ACCOUNT_STATUS", "users", id, body);
    return mapper.account(user);
  }

  public Object roles(UUID id, HttpServletRequest req) {
    account(id, req);
    return queries.findPageForRoles(Map.of("u", id), pages.request(req));
  }

  public RoleResponse role(long id, HttpServletRequest req) {
    permissions.check(req, null, "ADMIN");
    return assignments.findDetailsById(id).orElseThrow(ApiException::missing);
  }

  public IdResponse grant(UUID id, Map<String, Object> body, HttpServletRequest req) {
    UUID actor = permissions.check(req, null, "ADMIN");
    Input.only(body, "role_code", "campus_code");
    String role = Input.text(body, "role_code");
    if (!Set.of("STUDENT", "STAFF_EDITOR", "STAFF_REVIEWER", "TECH_OPERATOR", "ADMIN")
        .contains(role)) throw ApiException.invalid("Unknown role.");
    Object code = body.get("campus_code");
    if (code != null && Set.of("STUDENT", "TECH_OPERATOR", "ADMIN").contains(role))
      throw ApiException.invalid("This role must be global.");
    lock();
    var user = lockedUser(id);
    Short campus =
        code == null
            ? null
            : campuses.findAllByCode(code.toString()).stream()
                .findFirst()
                .orElseThrow(ApiException::missing)
                .getId();
    var assignment = new RoleAssignmentsEntity();
    assignment.setUserId(id);
    assignment.setRoleCode(role);
    assignment.setCampusId(campus);
    assignment.setAssignedAt(Instant.now());
    assignments.saveAndFlush(assignment);
    user.setAuthEpoch(user.getAuthEpoch() + 1);
    audit.record(actor, "GRANT_ROLE", "users", id, body);
    return mapper.roleId(assignment);
  }

  public void revoke(long id, HttpServletRequest req) {
    UUID actor = permissions.check(req, null, "ADMIN");
    lock();
    var assignment =
        assignments.findLockedById(id).stream().findFirst().orElseThrow(ApiException::missing);
    UUID userId = assignment.getUserId();
    assignments.delete(assignment);
    assignments.flush();
    var user = users.findById(userId).orElseThrow(ApiException::missing);
    user.setAuthEpoch(user.getAuthEpoch() + 1);
    invariant();
    audit.record(actor, "REVOKE_ROLE", "role_assignments", id, Map.of());
  }
}
