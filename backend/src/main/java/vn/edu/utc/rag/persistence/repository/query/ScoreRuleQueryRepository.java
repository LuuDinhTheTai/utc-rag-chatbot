package vn.edu.utc.rag.persistence.repository.query;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.utc.rag.persistence.internal.NativeSqlExecutor;

@Repository
@Transactional
public class ScoreRuleQueryRepository {

  private final NativeSqlExecutor query;

  public ScoreRuleQueryRepository(
      NativeSqlExecutor query, vn.edu.utc.rag.persistence.query.QueryPages queryPages) {
    this.query = query;
    this.queryPages = queryPages;
  }

  public Map<String, Object> findApiScoreRulesForForm(Map<String, ?> parameters) {
    return query.one(
        "SELECT id,version_id,formula_key,output_scale_code,source FROM api_score_rules WHERE"
            + " id=:id",
        parameters);
  }

  private final vn.edu.utc.rag.persistence.query.QueryPages queryPages;

  public Map<String, Object> findPageForRules(
      Map<String, ?> input, vn.edu.utc.rag.persistence.query.QueryPage req) {
    Map<String, Object> parameters = new HashMap<>(input);
    return queryPages.page(
        "SELECT id,campus_code,admission_year AS"
            + " year,method_code,version_id,output_scale_code,source,updated_at FROM"
            + " api_score_rules WHERE true"
            + vn.edu.utc.rag.persistence.query.QueryPages.filters(
                req,
                parameters,
                Map.of(
                    "campus_code",
                    "campus_code",
                    "year",
                    "admission_year",
                    "method_code",
                    "method_code")),
        parameters,
        req,
        "id:bigint:asc");
  }
}
