package vn.edu.utc.rag.controller.scoring;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import vn.edu.utc.rag.service.scoring.ScoreRuleService;

@RestController
@RequestMapping("/api/v1")
public class ScoringController {

  @GetMapping("/score-rules")
  Object rules(HttpServletRequest req) {
    return service.rules(req);
  }

  @GetMapping("/score-rules/{id}/form")
  Object form(@PathVariable String id) {
    return service.form(id);
  }

  @PostMapping("/score-calculations")
  Object calculate(@RequestBody Map<String, Object> b, HttpServletRequest req) {
    return service.calculate(b, req);
  }

  private final ScoreRuleService service;

  public ScoringController(ScoreRuleService service) {
    this.service = service;
  }
}
