package vn.edu.utc.rag.ingestion;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import org.springframework.http.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import vn.edu.utc.rag.common.*;
import vn.edu.utc.rag.security.*;

@RestController
@RequestMapping("/api/v1/ops")
public class OpsController {
  private final Db db;
  private final Permissions permissions;
  private final Pages pages;
  private final RateLimits limits;

  public OpsController(Db db, Permissions permissions, Pages pages, RateLimits limits) {
    this.db = db;
    this.permissions = permissions;
    this.pages = pages;
    this.limits = limits;
  }

  @GetMapping("/crawl-sources")
  Object sources(HttpServletRequest req) {
    permissions.check(req, null, "TECH_OPERATOR");
    return pages.page("SELECT * FROM crawl_sources", Map.of(), req, "id:bigint:asc");
  }

  @GetMapping("/crawl-jobs")
  Object jobs(HttpServletRequest req) {
    permissions.check(req, null, "TECH_OPERATOR");
    var args = new HashMap<String, Object>();
    return pages.page(
        "SELECT * FROM crawl_jobs WHERE true"
            + Pages.filters(req, args, Map.of("source_id", "source_id", "status", "status")),
        args,
        req,
        "id:bigint:desc");
  }

  @GetMapping("/crawl-jobs/{id}")
  Object job(@PathVariable String id, HttpServletRequest req) {
    permissions.check(req, null, "TECH_OPERATOR");
    return db.one("SELECT * FROM crawl_jobs WHERE id=:id", Map.of("id", Input.id(id)));
  }

  @Transactional
  @PostMapping("/crawl-jobs")
  ResponseEntity<?> create(@RequestBody Map<String, Object> b, HttpServletRequest req) {
    var actor = permissions.check(req, null, "TECH_OPERATOR");
    limits.hit("job:" + actor, 10, Duration.ofMinutes(1));
    Input.only(b, "source_id");
    long source = Input.id(b.get("source_id"));
    var row = db.one("SELECT * FROM crawl_sources WHERE id=:id FOR UPDATE", Map.of("id", source));
    if (!Boolean.TRUE.equals(row.get("enabled")))
      throw ApiException.conflict("Source is disabled.");
    var active =
        db.list(
            "SELECT id FROM crawl_jobs WHERE source_id=:id AND status IN ('PENDING','RUNNING')",
            Map.of("id", source));
    if (!active.isEmpty())
      return ResponseEntity.status(409)
          .contentType(MediaType.APPLICATION_PROBLEM_JSON)
          .location(URI.create("/api/v1/ops/crawl-jobs/" + active.getFirst().get("id")))
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
                  active.getFirst().get("id")));
    var job =
        db.one(
            "INSERT INTO crawl_jobs(source_id,requested_by,status,run_after,attempt_no)"
                + " VALUES(:s,:u,'PENDING',now(),0) RETURNING *",
            Map.of("s", source, "u", actor));
    db.audit(actor, "CREATE_JOB", "crawl_jobs", job.get("id"), Map.of());
    return ResponseEntity.accepted()
        .location(URI.create("/api/v1/ops/crawl-jobs/" + job.get("id")))
        .body(job);
  }
}
