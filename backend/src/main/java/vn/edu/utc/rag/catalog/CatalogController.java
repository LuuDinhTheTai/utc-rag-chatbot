package vn.edu.utc.rag.catalog;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import vn.edu.utc.rag.common.*;

@RestController
@RequestMapping("/api/v1")
public class CatalogController {

  @GetMapping("/campuses")
  Object campuses(HttpServletRequest req) {
    return service.campuses(req);
  }

  @GetMapping("/chat-suggestions")
  Object suggestions(HttpServletRequest req) {
    return service.suggestions(req);
  }

  @GetMapping("/admission-cycles")
  Object cycles(HttpServletRequest req) {
    return service.cycles(req);
  }

  @GetMapping("/admission-cycles/{id}")
  Object cycle(@PathVariable String id) {
    return service.cycle(id);
  }

  @GetMapping("/admission-cycles/{id}/methods")
  Object methods(@PathVariable String id, HttpServletRequest req) {
    return service.methods(id, req);
  }

  @GetMapping("/admission-cycles/{id}/options")
  Object options(@PathVariable String id, HttpServletRequest req) {
    return service.options(id, req);
  }

  @GetMapping("/admission-options/{id}/subject-combinations")
  Object combinations(@PathVariable String id, HttpServletRequest req) {
    return service.combinations(id, req);
  }

  @GetMapping("/admission-cutoffs")
  Object cutoffs(HttpServletRequest req) {
    return service.cutoffs(req);
  }

  @GetMapping("/programs")
  Object programs(HttpServletRequest req) {
    return service.programs(req);
  }

  @GetMapping("/programs/{id}")
  Object program(@PathVariable String id) {
    return service.program(id);
  }

  @GetMapping("/program-offerings")
  Object offerings(HttpServletRequest req) {
    return service.offerings(req);
  }

  @GetMapping("/program-offerings/{id}/curriculum")
  Object curriculum(@PathVariable String id) {
    return service.curriculum(id);
  }

  @GetMapping("/program-offerings/{id}/courses")
  Object courses(@PathVariable String id, HttpServletRequest req) {
    return service.courses(id, req);
  }

  @GetMapping("/questionnaires")
  Object questionnaires(HttpServletRequest req) {
    return service.questionnaires(req);
  }

  @GetMapping("/questionnaires/{id}/questions")
  Object questions(
      @PathVariable String id, @RequestParam("version_id") String version, HttpServletRequest req) {
    return service.questions(id, version, req);
  }

  @GetMapping("/guides")
  Object guides(HttpServletRequest req) {
    return service.guides(req);
  }

  @GetMapping("/guides/{id}")
  Object guide(@PathVariable String id) {
    return service.guide(id);
  }

  @GetMapping("/guides/{id}/entries")
  Object entries(@PathVariable String id, HttpServletRequest req) {
    return service.entries(id, req);
  }

  @GetMapping("/schedule-events")
  Object schedule(HttpServletRequest req) {
    return service.schedule(req);
  }

  @GetMapping("/financial-policies")
  Object finances(HttpServletRequest req) {
    return service.finances(req);
  }

  @GetMapping("/financial-policies/{id}")
  Object finance(@PathVariable String id) {
    return service.finance(id);
  }

  private final CatalogService service;

  public CatalogController(CatalogService service) {
    this.service = service;
  }
}
