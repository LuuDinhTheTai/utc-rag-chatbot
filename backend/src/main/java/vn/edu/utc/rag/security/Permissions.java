package vn.edu.utc.rag.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.stereotype.Component;
import vn.edu.utc.rag.common.*;

@Component
public class Permissions {
  private final Db db;
  private final Access access;

  public Permissions(Db db, Access access) {
    this.db = db;
    this.access = access;
  }

  public UUID check(HttpServletRequest req, Object campus, String... roles) {
    UUID user = access.requireUser(req);
    var assignments =
        db.list(
            "SELECT role_code,campus_id FROM role_assignments WHERE user_id=:id",
            Map.of("id", user));
    var matching =
        assignments.stream()
            .filter(r -> Arrays.asList(roles).contains(r.get("role_code")))
            .toList();
    if (matching.isEmpty())
      throw new ApiException(403, "ROLE_REQUIRED", "Required role is missing.");
    if (matching.stream()
        .noneMatch(
            r ->
                r.get("campus_id") == null
                    || (campus != null && r.get("campus_id").toString().equals(campus.toString()))))
      throw ApiException.missing();
    return user;
  }

  public String campusFilter(HttpServletRequest req, String column, String... roles) {
    UUID user = access.requireUser(req);
    var rows =
        db
            .list(
                "SELECT campus_id,role_code FROM role_assignments WHERE user_id=:id",
                Map.of("id", user))
            .stream()
            .filter(r -> Arrays.asList(roles).contains(r.get("role_code")))
            .toList();
    if (rows.isEmpty()) throw new ApiException(403, "ROLE_REQUIRED", "Required role is missing.");
    if (rows.stream().anyMatch(r -> r.get("campus_id") == null)) return "true";
    return column
        + " IN ("
        + rows.stream()
            .map(r -> Long.toString(Long.parseLong(r.get("campus_id").toString())))
            .distinct()
            .collect(java.util.stream.Collectors.joining(","))
        + ")";
  }
}
