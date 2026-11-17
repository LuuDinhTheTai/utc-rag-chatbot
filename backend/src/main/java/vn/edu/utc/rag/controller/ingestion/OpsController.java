package vn.edu.utc.rag.controller.ingestion;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import vn.edu.utc.rag.security.*;
import vn.edu.utc.rag.service.ingestion.OpsService;

@RestController
@RequestMapping("/api/v1/ops")
public class OpsController {

  @GetMapping("/crawl-sources")
  Object sources(HttpServletRequest req) {
    return service.sources(req);
  }

  @GetMapping("/crawl-jobs")
  Object jobs(HttpServletRequest req) {
    return service.jobs(req);
  }

  @GetMapping("/crawl-jobs/{id}")
  Object job(@PathVariable String id, HttpServletRequest req) {
    return service.job(id, req);
  }

  @PostMapping("/crawl-jobs")
  ResponseEntity<?> create(@RequestBody Map<String, Object> b, HttpServletRequest req) {
    var result = service.create(b, req);
    var job = result.job();
    var location = URI.create("/api/v1/ops/crawl-jobs/" + job.get("id"));
    if (result.alreadyActive())
      return ResponseEntity.status(409)
          .contentType(MediaType.APPLICATION_PROBLEM_JSON)
          .location(location)
          .body(
              Map.of(
                  "type",
                  "/api/problems/conflict",
                  "status",
                  409,
                  "code",
                  "CONFLICT",
                  "title",
                  "A job is already active.",
                  "request_id",
                  req.getAttribute("request_id"),
                  "errors",
                  List.of(),
                  "job_id",
                  job.get("id")));
    return ResponseEntity.accepted().location(location).body(job);
  }

  private final OpsService service;

  public OpsController(OpsService service) {
    this.service = service;
  }
}
