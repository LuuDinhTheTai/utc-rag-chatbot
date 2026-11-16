package vn.edu.utc.rag.scoring;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import vn.edu.utc.rag.common.*;

@RestController
@RequestMapping("/api/v1")
public class ScoringController {
  private final Db db;
  private final Pages pages;
  private final ScoringService service;

  public ScoringController(Db db, Pages pages, ScoringService service) {
    this.db = db;
    this.pages = pages;
    this.service = service;
  }

  @GetMapping("/score-rules")
  Object rules(HttpServletRequest req) {
    var args = new HashMap<String, Object>();
    return pages.page(
        "SELECT id,campus_code,admission_year AS"
            + " year,method_code,version_id,output_scale_code,source,updated_at FROM"
            + " api_score_rules WHERE true"
            + Pages.filters(
                req,
                args,
                Map.of(
                    "campus_code",
                    "campus_code",
                    "year",
                    "admission_year",
                    "method_code",
                    "method_code")),
        args,
        req,
        "id:bigint:asc");
  }

  @GetMapping("/score-rules/{id}/form")
  Object form(@PathVariable String id) {
    var rule =
        db.one(
            "SELECT id,version_id,formula_key,output_scale_code,source FROM api_score_rules WHERE"
                + " id=:id",
            Map.of("id", Input.id(id)));
    var inputs =
        db.list(
            "SELECT id,input_kind,input_code,label,is_required,min_value,max_value FROM"
                + " score_rule_inputs WHERE rule_version_id=:v ORDER BY id",
            Map.of("v", Input.id(rule.get("version_id"))));
    for (var input : inputs)
      input.put(
          "choices",
          db.list(
              "SELECT match_code FROM score_rule_bands WHERE rule_input_id=:id AND match_code IS"
                  + " NOT NULL ORDER BY id",
              Map.of("id", Input.id(input.get("id")))));
    rule.put("inputs", inputs);
    return rule;
  }

  @PostMapping("/score-calculations")
  Object calculate(@RequestBody Map<String, Object> b, HttpServletRequest req) {
    return service.calculate(b, req);
  }
}
