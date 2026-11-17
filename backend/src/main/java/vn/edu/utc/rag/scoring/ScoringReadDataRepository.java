package vn.edu.utc.rag.scoring;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.utc.rag.persistence.internal.NativeSqlExecutor;

@Repository
@Transactional
public class ScoringReadDataRepository {

  private final NativeSqlExecutor query;

  public ScoringReadDataRepository(
      NativeSqlExecutor query,
      vn.edu.utc.rag.persistence.internal.EntityViews views,
      vn.edu.utc.rag.persistence.repository.ScoreRuleBandsRepository scoreRuleBandsJpa,
      vn.edu.utc.rag.persistence.repository.ScoreRuleInputsRepository scoreRuleInputsJpa,
      vn.edu.utc.rag.persistence.query.QueryPages queryPages) {
    this.query = query;
    this.views = views;
    this.scoreRuleBandsJpa = scoreRuleBandsJpa;
    this.scoreRuleInputsJpa = scoreRuleInputsJpa;
    this.queryPages = queryPages;
  }

  public Map<String, Object> findApiScoreRulesForForm(Map<String, ?> parameters) {
    return query.one(
        "SELECT id,version_id,formula_key,output_scale_code,source FROM api_score_rules WHERE"
            + " id=:id",
        parameters);
  }

  public List<Map<String, Object>> findScoreRuleInputsForForm(Map<String, ?> parameters) {
    var result =
        scoreRuleInputsJpa.findAllByRuleVersionIdOrderByIdAsc(
            (Long) views.value(parameters, "v", Long.class));
    var rows =
        result.stream()
            .map(
                e ->
                    views.project(
                        e, "id,input_kind,input_code,label,is_required,min_value,max_value"))
            .toList();
    return rows;
  }

  public List<Map<String, Object>> findScoreRuleBandsForForm(Map<String, ?> parameters) {
    var result =
        scoreRuleBandsJpa.findAllByRuleInputIdAndMatchCodeIsNotNullOrderByIdAsc(
            (Long) views.value(parameters, "id", Long.class));
    var rows = result.stream().map(e -> views.project(e, "match_code")).toList();
    return rows;
  }

  private final vn.edu.utc.rag.persistence.internal.EntityViews views;

  private final vn.edu.utc.rag.persistence.repository.ScoreRuleBandsRepository scoreRuleBandsJpa;

  private final vn.edu.utc.rag.persistence.repository.ScoreRuleInputsRepository scoreRuleInputsJpa;

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
