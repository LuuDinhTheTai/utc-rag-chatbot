package vn.edu.utc.rag.persistence.repository.query;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional
public class OpsQueryRepository {

  public OpsQueryRepository(vn.edu.utc.rag.persistence.query.QueryPages queryPages) {
    this.queryPages = queryPages;
  }

  private final vn.edu.utc.rag.persistence.query.QueryPages queryPages;

  public Map<String, Object> findPageForSources(
      Map<String, ?> input, vn.edu.utc.rag.persistence.query.QueryPage req) {
    Map<String, Object> parameters = new HashMap<>(input);
    return queryPages.page("SELECT * FROM crawl_sources", parameters, req, "id:bigint:asc");
  }

  public Map<String, Object> findPageForJobs(
      Map<String, ?> input, vn.edu.utc.rag.persistence.query.QueryPage req) {
    Map<String, Object> parameters = new HashMap<>(input);
    return queryPages.page(
        "SELECT * FROM crawl_jobs WHERE true"
            + vn.edu.utc.rag.persistence.query.QueryPages.filters(
                req, parameters, Map.of("source_id", "source_id", "status", "status")),
        parameters,
        req,
        "id:bigint:desc");
  }
}
