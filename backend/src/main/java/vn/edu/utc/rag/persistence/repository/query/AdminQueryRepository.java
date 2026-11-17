package vn.edu.utc.rag.persistence.repository.query;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.utc.rag.persistence.internal.NativeSqlExecutor;

@Repository
@Transactional
public class AdminQueryRepository {

  private final NativeSqlExecutor query;

  public AdminQueryRepository(
      NativeSqlExecutor query, vn.edu.utc.rag.persistence.query.QueryPages queryPages) {
    this.query = query;
    this.queryPages = queryPages;
  }

  public int applyUsersForLock(Map<String, ?> parameters) {
    return query.update("LOCK TABLE users IN SHARE ROW EXCLUSIVE MODE", parameters);
  }

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
