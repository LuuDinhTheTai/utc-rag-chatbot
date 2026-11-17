package vn.edu.utc.rag.persistence.repository.query;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional
public class EditorialQueryRepository {

  public EditorialQueryRepository(vn.edu.utc.rag.persistence.query.QueryPages queryPages) {
    this.queryPages = queryPages;
  }

  private final vn.edu.utc.rag.persistence.query.QueryPages queryPages;

  public Map<String, Object> findPageForList(
      Map<String, ?> input,
      vn.edu.utc.rag.persistence.query.QueryPage req,
      vn.edu.utc.rag.domain.editorial.EditorialKind k,
      vn.edu.utc.rag.persistence.query.CampusScope scope) {
    Map<String, Object> parameters = new HashMap<>(input);
    String filter =
        vn.edu.utc.rag.persistence.query.QueryPages.filters(
            req,
            parameters,
            Map.of(
                "year",
                k.identity().contains("entry_year") ? "t.entry_year" : "t.admission_year",
                "campus_code",
                "c.code"));
    return queryPages.page(
        "SELECT t.*,c.code AS campus_code FROM "
            + k.table()
            + " t JOIN campuses c ON c.id=t.campus_id WHERE "
            + scope.predicate("t.campus_id")
            + filter,
        parameters,
        req,
        "id:bigint:asc");
  }

  public Map<String, Object> findPageForVersions(
      Map<String, ?> input,
      vn.edu.utc.rag.persistence.query.QueryPage req,
      vn.edu.utc.rag.domain.editorial.EditorialKind k) {
    Map<String, Object> parameters = new HashMap<>(input);
    String filter =
        vn.edu.utc.rag.persistence.query.QueryPages.filters(
            req, parameters, Map.of("status", "status"));
    return queryPages.page(
        "SELECT * FROM " + k.versions() + " WHERE " + k.parent() + "=:parent" + filter,
        parameters,
        req,
        "id:bigint:asc");
  }
}
