package vn.edu.utc.rag.controller.personality;

import jakarta.servlet.http.HttpServletRequest;
import java.io.*;
import java.net.URI;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import vn.edu.utc.rag.service.personality.PersonalityReportService;
import vn.edu.utc.rag.service.personality.PersonalityService;
import vn.edu.utc.rag.validation.Input;

@RestController
@RequestMapping("/api/v1")
public class PersonalityController {

  private final PersonalityService service;

  public PersonalityController(PersonalityService service, PersonalityReportService reports) {
    this.service = service;
    this.reports = reports;
  }

  @PostMapping("/personality-attempts")
  ResponseEntity<?> create(@RequestBody Map<String, Object> b, HttpServletRequest req) {
    var r = (Map<?, ?>) service.create(b, req);
    return ResponseEntity.created(URI.create("/api/v1/personality-attempts/" + r.get("id")))
        .body(r);
  }

  @GetMapping("/personality-attempts/{id}")
  Object get(@PathVariable String id, HttpServletRequest r) {
    return service.get(Input.uuid(id), r);
  }

  @GetMapping("/personality-attempts/{id}/questions")
  Object questions(@PathVariable String id, HttpServletRequest r) {
    return service.questions(Input.uuid(id), r);
  }

  @PutMapping("/personality-attempts/{id}/answers")
  ResponseEntity<?> answers(
      @PathVariable String id, @RequestBody Map<String, Object> b, HttpServletRequest r) {
    service.answers(Input.uuid(id), b, r);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/personality-attempts/{id}/result")
  ResponseEntity<?> complete(@PathVariable String id, HttpServletRequest r) {
    return ResponseEntity.status(201).body(service.complete(Input.uuid(id), r));
  }

  @GetMapping("/personality-attempts/{id}/result")
  Object result(@PathVariable String id, HttpServletRequest r) {
    return service.result(Input.uuid(id), r);
  }

  @GetMapping("/personality-attempts/{id}/recommendations")
  Object recommendations(@PathVariable String id, HttpServletRequest r) {
    return service.recommendations(Input.uuid(id), r);
  }

  @PutMapping("/personality-attempts/{id}/saved-result")
  ResponseEntity<?> save(@PathVariable String id, HttpServletRequest r) {
    service.save(Input.uuid(id), r);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/me/personality-results")
  Object history(HttpServletRequest r) {
    return service.history(r);
  }

  @GetMapping(
      value = "/personality-attempts/{id}/report",
      produces = MediaType.APPLICATION_PDF_VALUE)
  ResponseEntity<byte[]> report(@PathVariable String id, HttpServletRequest r) throws IOException {
    return ResponseEntity.ok()
        .header("Content-Disposition", "attachment; filename=personality-result.pdf")
        .body(reports.export(Input.uuid(id), r));
  }

  private final PersonalityReportService reports;
}
