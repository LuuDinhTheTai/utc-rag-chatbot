package vn.edu.utc.rag.service.scoring;

import jakarta.servlet.http.HttpServletRequest;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.utc.rag.persistence.repository.query.ScoreRuleQueryRepository;
import vn.edu.utc.rag.validation.Input;
import vn.edu.utc.rag.web.pagination.PageRequestFactory;

@Service()
@Transactional()
public class ScoreRuleService {

  private final PageRequestFactory pages;

  private final ScoringService service;

  public ScoreRuleService(
      PageRequestFactory pages,
      ScoringService service,
      ScoreRuleQueryRepository repository,
      vn.edu.utc.rag.mapper.common.EntityMapper views,
      vn.edu.utc.rag.persistence.repository.ScoreRuleInputsRepository scoreRuleInputsJpa,
      vn.edu.utc.rag.persistence.repository.ScoreRuleBandsRepository scoreRuleBandsJpa) {
    this.pages = pages;
    this.service = service;
    this.repository = repository;
    this.views = views;
    this.scoreRuleInputsJpa = scoreRuleInputsJpa;
    this.scoreRuleBandsJpa = scoreRuleBandsJpa;
  }

  public Object rules(HttpServletRequest req) {
    var args = new HashMap<String, Object>();
    return repository.findPageForRules(args, pages.request(req));
  }

  public Object form(String id) {
    var rule = repository.findApiScoreRulesForForm(Map.of("id", Input.id(id)));
    var inputs = findScoreRuleInputsForForm(Map.of("v", Input.id(rule.get("version_id"))));
    for (var input : inputs)
      input.put("choices", findScoreRuleBandsForForm(Map.of("id", Input.id(input.get("id")))));
    rule.put("inputs", inputs);
    return rule;
  }

  public Object calculate(Map<String, Object> b, HttpServletRequest req) {
    return service.calculate(b, req);
  }

  private final ScoreRuleQueryRepository repository;

  private List<Map<String, Object>> findScoreRuleInputsForForm(Map<String, ?> parameters) {
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

  private final vn.edu.utc.rag.mapper.common.EntityMapper views;

  private final vn.edu.utc.rag.persistence.repository.ScoreRuleInputsRepository scoreRuleInputsJpa;

  private List<Map<String, Object>> findScoreRuleBandsForForm(Map<String, ?> parameters) {
    var result =
        scoreRuleBandsJpa.findAllByRuleInputIdAndMatchCodeIsNotNullOrderByIdAsc(
            (Long) views.value(parameters, "id", Long.class));
    var rows = result.stream().map(e -> views.project(e, "match_code")).toList();
    return rows;
  }

  private final vn.edu.utc.rag.persistence.repository.ScoreRuleBandsRepository scoreRuleBandsJpa;
}
