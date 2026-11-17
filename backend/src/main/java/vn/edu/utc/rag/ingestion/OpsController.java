package vn.edu.utc.rag.ingestion;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import vn.edu.utc.rag.common.*;
import vn.edu.utc.rag.security.*;

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
    return service.create(b, req);
  }

  private final OpsService service;

  public OpsController(OpsService service) {
    this.service = service;
  }
}
