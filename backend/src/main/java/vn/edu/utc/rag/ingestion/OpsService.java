package vn.edu.utc.rag.ingestion;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import vn.edu.utc.rag.common.*;
import vn.edu.utc.rag.security.*;

@Service()
public class OpsService {

  private final Permissions permissions;

  private final Pages pages;

  private final RateLimits limits;

  public OpsService(
      Permissions permissions,
      Pages pages,
      RateLimits limits,
      OpsDataRepository repository,
      vn.edu.utc.rag.persistence.repository.AuditRepository audit) {
    this.permissions = permissions;
    this.pages = pages;
    this.limits = limits;
    this.repository = repository;
    this.audit = audit;
  }

  public Object sources(HttpServletRequest req) {
    permissions.check(req, null, "TECH_OPERATOR");
    return repository.findPageForSources(Map.of(), pages.request(req));
  }

  public Object jobs(HttpServletRequest req) {
    permissions.check(req, null, "TECH_OPERATOR");
    var args = new HashMap<String, Object>();
    return repository.findPageForJobs(args, pages.request(req));
  }

  public Object job(String id, HttpServletRequest req) {
    permissions.check(req, null, "TECH_OPERATOR");
    return repository.findCrawlJobsForJob(Map.of("id", Input.id(id)));
  }

  @Transactional
  public ResponseEntity<?> create(Map<String, Object> b, HttpServletRequest req) {
    var actor = permissions.check(req, null, "TECH_OPERATOR");
    limits.hit("job:" + actor, 10, Duration.ofMinutes(1));
    Input.only(b, "source_id");
    long source = Input.id(b.get("source_id"));
    var row = repository.findCrawlSourcesForCreate(Map.of("id", source));
    if (!Boolean.TRUE.equals(row.get("enabled")))
      throw ApiException.conflict("Source is disabled.");
    var active = repository.findCrawlJobsForCreate(Map.of("id", source));
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
    var job = repository.findCrawlJobsForCreate2(Map.of("s", source, "u", actor));
    audit.record(actor, "CREATE_JOB", "crawl_jobs", job.get("id"), Map.of());
    return ResponseEntity.accepted()
        .location(URI.create("/api/v1/ops/crawl-jobs/" + job.get("id")))
        .body(job);
  }

  private final OpsDataRepository repository;

  private final vn.edu.utc.rag.persistence.repository.AuditRepository audit;
}
