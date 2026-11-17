package vn.edu.utc.rag.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.stereotype.Component;
import vn.edu.utc.rag.exception.ApiException;

@Component
public class Permissions {
  private final vn.edu.utc.rag.persistence.repository.RoleAssignmentsRepository repository;

  private final Access access;

  public Permissions(
      Access access, vn.edu.utc.rag.persistence.repository.RoleAssignmentsRepository repository) {
    this.access = access;
    this.repository = repository;
  }

  public UUID check(HttpServletRequest req, Object campus, String... roles) {
    UUID user = access.requireUser(req);
    var assignments = repository.findAllByUserId(user);
    var matching =
        assignments.stream().filter(r -> Arrays.asList(roles).contains(r.getRoleCode())).toList();
    if (matching.isEmpty())
      throw new ApiException(403, "ROLE_REQUIRED", "Required role is missing.");
    if (matching.stream()
        .noneMatch(
            r ->
                r.getCampusId() == null
                    || (campus != null && r.getCampusId().toString().equals(campus.toString()))))
      throw ApiException.missing();
    return user;
  }

  public vn.edu.utc.rag.persistence.query.CampusScope campusScope(
      HttpServletRequest req, String... roles) {
    UUID user = access.requireUser(req);
    var rows =
        repository.findAllByUserId(user).stream()
            .filter(r -> Arrays.asList(roles).contains(r.getRoleCode()))
            .toList();
    if (rows.isEmpty()) throw new ApiException(403, "ROLE_REQUIRED", "Required role is missing.");
    if (rows.stream().anyMatch(r -> r.getCampusId() == null))
      return new vn.edu.utc.rag.persistence.query.CampusScope(true, Set.of());
    return new vn.edu.utc.rag.persistence.query.CampusScope(
        false,
        rows.stream()
            .map(r -> Short.valueOf(r.getCampusId().toString()))
            .collect(java.util.stream.Collectors.toSet()));
  }
}
