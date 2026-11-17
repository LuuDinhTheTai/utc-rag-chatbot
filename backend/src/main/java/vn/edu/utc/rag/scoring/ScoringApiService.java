package vn.edu.utc.rag.scoring;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import vn.edu.utc.rag.common.*;

@Service()
public class ScoringApiService {

  private final Pages pages;

  private final ScoringService service;

  public ScoringApiService(
      Pages pages, ScoringService service, ScoringReadDataRepository repository) {
    this.pages = pages;
    this.service = service;
    this.repository = repository;
  }

  public Object rules(HttpServletRequest req) {
    var args = new HashMap<String, Object>();
    return repository.findPageForRules(args, pages.request(req));
  }

  public Object form(String id) {
    var rule = repository.findApiScoreRulesForForm(Map.of("id", Input.id(id)));
    var inputs =
        repository.findScoreRuleInputsForForm(Map.of("v", Input.id(rule.get("version_id"))));
    for (var input : inputs)
      input.put(
          "choices", repository.findScoreRuleBandsForForm(Map.of("id", Input.id(input.get("id")))));
    rule.put("inputs", inputs);
    return rule;
  }

  public Object calculate(Map<String, Object> b, HttpServletRequest req) {
    return service.calculate(b, req);
  }

  private final ScoringReadDataRepository repository;
}
