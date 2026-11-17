package vn.edu.utc.rag.administration;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.utc.rag.persistence.internal.NativeSqlExecutor;

@Repository
@Transactional
public class AdminDataRepository {

  private final NativeSqlExecutor query;

  public AdminDataRepository(
      NativeSqlExecutor query,
      vn.edu.utc.rag.persistence.internal.EntityViews views,
      vn.edu.utc.rag.persistence.repository.CampusesRepository campusesJpa,
      vn.edu.utc.rag.persistence.repository.RoleAssignmentsRepository roleAssignmentsJpa,
      vn.edu.utc.rag.persistence.repository.UsersRepository usersJpa,
      vn.edu.utc.rag.persistence.query.QueryPages queryPages) {
    this.query = query;
    this.views = views;
    this.campusesJpa = campusesJpa;
    this.roleAssignmentsJpa = roleAssignmentsJpa;
    this.usersJpa = usersJpa;
    this.queryPages = queryPages;
  }

  public Map<String, Object> findUsersForAccount(Map<String, ?> parameters) {
    var result =
        usersJpa.findById((java.util.UUID) views.value(parameters, "id", java.util.UUID.class));
    var rows =
        result.stream()
            .map(e -> views.project(e, "id,email,status,auth_epoch,created_at"))
            .toList();
    if (rows.isEmpty()) throw vn.edu.utc.rag.common.ApiException.missing();
    return rows.getFirst();
  }

  public int applyUsersForLock(Map<String, ?> parameters) {
    return query.update("LOCK TABLE users IN SHARE ROW EXCLUSIVE MODE", parameters);
  }

  public Map<String, Object> findUsersForInvariant(Map<String, ?> parameters) {
    return Map.of("n", usersJpa.countActiveAdministrators().toString());
  }

  public Map<String, Object> findUsersForStatus(Map<String, ?> parameters) {
    var result =
        usersJpa.findLockedById(
            (java.util.UUID) views.value(parameters, "id", java.util.UUID.class));
    var rows = result.stream().map(e -> views.project(e, "id")).toList();
    if (rows.isEmpty()) throw vn.edu.utc.rag.common.ApiException.missing();
    return rows.getFirst();
  }

  public int applyUsersForStatus(Map<String, ?> parameters) {
    var user =
        usersJpa
            .findById((UUID) parameters.get("id"))
            .orElseThrow(vn.edu.utc.rag.common.ApiException::missing);
    user.setStatus((String) parameters.get("status"));
    user.setAuthEpoch(user.getAuthEpoch() + 1);
    return 1;
  }

  public Map<String, Object> findUsersForStatus2(Map<String, ?> parameters) {
    var result = usersJpa.findById((UUID) views.value(parameters, "id", UUID.class));
    var rows =
        result.stream()
            .map(e -> views.project(e, "id,email,status,auth_epoch,created_at"))
            .toList();
    if (rows.isEmpty()) throw vn.edu.utc.rag.common.ApiException.missing();
    return rows.getFirst();
  }

  public Map<String, Object> findRoleAssignmentsForRole(Map<String, ?> parameters) {
    return views.of(
        roleAssignmentsJpa
            .findDetailsById(((Number) parameters.get("id")).longValue())
            .orElseThrow(vn.edu.utc.rag.common.ApiException::missing));
  }

  public Map<String, Object> findUsersForGrant(Map<String, ?> parameters) {
    var result = usersJpa.findLockedById((UUID) views.value(parameters, "id", UUID.class));
    var rows = result.stream().map(e -> views.project(e, "id")).toList();
    if (rows.isEmpty()) throw vn.edu.utc.rag.common.ApiException.missing();
    return rows.getFirst();
  }

  public Map<String, Object> findCampusesForGrant(Map<String, ?> parameters) {
    var result = campusesJpa.findAllByCode((String) views.value(parameters, "code", String.class));
    var rows = result.stream().map(e -> views.project(e, "id")).toList();
    if (rows.isEmpty()) throw vn.edu.utc.rag.common.ApiException.missing();
    return rows.getFirst();
  }

  public Map<String, Object> findRoleAssignmentsForGrant(Map<String, ?> parameters) {
    var entity = new vn.edu.utc.rag.persistence.entity.RoleAssignmentsEntity();
    entity.setUserId((UUID) views.value(parameters, "id", UUID.class));
    entity.setRoleCode((String) views.value(parameters, "role", String.class));
    entity.setCampusId((Short) views.value(parameters, "campus", Short.class));
    entity.setAssignedAt(java.time.Instant.now());
    var saved = roleAssignmentsJpa.saveAndFlush(entity);
    return views.project(saved, "id");
  }

  public int applyUsersForGrant(Map<String, ?> parameters) {
    var user =
        usersJpa
            .findById((UUID) parameters.get("id"))
            .orElseThrow(vn.edu.utc.rag.common.ApiException::missing);
    user.setAuthEpoch(user.getAuthEpoch() + 1);
    return 1;
  }

  public Map<String, Object> findRoleAssignmentsForRevoke(Map<String, ?> parameters) {
    var result =
        roleAssignmentsJpa.findLockedById((Long) views.value(parameters, "id", Long.class));
    var rows = result.stream().map(e -> views.project(e, "*")).toList();
    if (rows.isEmpty()) throw vn.edu.utc.rag.common.ApiException.missing();
    return rows.getFirst();
  }

  public int applyRoleAssignmentsForRevoke(Map<String, ?> parameters) {
    {
      roleAssignmentsJpa.deleteById((Long) views.value(parameters, "id", Long.class));
      roleAssignmentsJpa.flush();
      return 1;
    }
  }

  public int applyUsersForRevoke(Map<String, ?> parameters) {
    return applyUsersForGrant(parameters);
  }

  private final vn.edu.utc.rag.persistence.internal.EntityViews views;

  private final vn.edu.utc.rag.persistence.repository.CampusesRepository campusesJpa;

  private final vn.edu.utc.rag.persistence.repository.RoleAssignmentsRepository roleAssignmentsJpa;

  private final vn.edu.utc.rag.persistence.repository.UsersRepository usersJpa;

  private final vn.edu.utc.rag.persistence.query.QueryPages queryPages;

  public Map<String, Object> findPageForAccounts(
      Map<String, ?> input, vn.edu.utc.rag.persistence.query.QueryPage req) {
    Map<String, Object> parameters = new HashMap<>(input);
    String sql =
        "SELECT id,email,status,auth_epoch,created_at FROM users WHERE true"
            + vn.edu.utc.rag.persistence.query.QueryPages.filters(
                req, parameters, Map.of("status", "status"));
    if (req.getParameter("q") != null) {
      sql += " AND email ILIKE :q";
      parameters.put("q", "%" + req.getParameter("q") + "%");
    }
    return queryPages.page(sql, parameters, req, "id:uuid:asc");
  }

  public Map<String, Object> findPageForRoles(
      Map<String, ?> input, vn.edu.utc.rag.persistence.query.QueryPage req) {
    Map<String, Object> parameters = new HashMap<>(input);
    return queryPages.page(
        "SELECT r.id,r.role_code,c.code AS campus_code,r.assigned_at FROM role_assignments r LEFT"
            + " JOIN campuses c ON c.id=r.campus_id WHERE r.user_id=:u"
            + vn.edu.utc.rag.persistence.query.QueryPages.filters(
                req, parameters, Map.of("campus_code", "c.code")),
        parameters,
        req,
        "id:bigint:asc");
  }
}
