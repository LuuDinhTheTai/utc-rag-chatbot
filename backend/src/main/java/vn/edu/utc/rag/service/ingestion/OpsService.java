package vn.edu.utc.rag.service.ingestion;

import jakarta.servlet.http.HttpServletRequest;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.utc.rag.dto.ingestion.CrawlJobResult;
import vn.edu.utc.rag.exception.ApiException;
import vn.edu.utc.rag.persistence.repository.query.OpsQueryRepository;
import vn.edu.utc.rag.security.*;
import vn.edu.utc.rag.validation.Input;
import vn.edu.utc.rag.web.pagination.PageRequestFactory;

@Service()
@Transactional()
public class OpsService {

  private final Permissions permissions;

  private final PageRequestFactory pages;

  private final RateLimits limits;

  public OpsService(
      Permissions permissions,
      PageRequestFactory pages,
      RateLimits limits,
      OpsQueryRepository repository,
      vn.edu.utc.rag.service.audit.AuditService audit,
      vn.edu.utc.rag.mapper.common.EntityMapper views,
      vn.edu.utc.rag.persistence.repository.CrawlJobsRepository crawlJobsJpa,
      vn.edu.utc.rag.persistence.repository.CrawlSourcesRepository crawlSourcesJpa) {
    this.permissions = permissions;
    this.pages = pages;
    this.limits = limits;
    this.repository = repository;
    this.audit = audit;
    this.views = views;
    this.crawlJobsJpa = crawlJobsJpa;
    this.crawlSourcesJpa = crawlSourcesJpa;
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
    return findCrawlJobsForJob(Map.of("id", Input.id(id)));
  }

  @Transactional
  public CrawlJobResult create(Map<String, Object> b, HttpServletRequest req) {
    var actor = permissions.check(req, null, "TECH_OPERATOR");
    limits.hit("job:" + actor, 10, Duration.ofMinutes(1));
    Input.only(b, "source_id");
    long source = Input.id(b.get("source_id"));
    var row = findCrawlSourcesForCreate(Map.of("id", source));
    if (!Boolean.TRUE.equals(row.get("enabled")))
      throw ApiException.conflict("Source is disabled.");
    var active = findCrawlJobsForCreate(Map.of("id", source));
    if (!active.isEmpty()) return new CrawlJobResult(active.getFirst(), true);
    var job = findCrawlJobsForCreate2(Map.of("s", source, "u", actor));
    audit.record(actor, "CREATE_JOB", "crawl_jobs", job.get("id"), Map.of());
    return new CrawlJobResult(job, false);
  }

  private final OpsQueryRepository repository;

  private final vn.edu.utc.rag.service.audit.AuditService audit;

  private Map<String, Object> findCrawlJobsForJob(Map<String, ?> parameters) {
    var result = crawlJobsJpa.findById((Long) views.value(parameters, "id", Long.class));
    var rows = result.stream().map(e -> views.project(e, "*")).toList();
    if (rows.isEmpty()) throw vn.edu.utc.rag.exception.ApiException.missing();
    return rows.getFirst();
  }

  private final vn.edu.utc.rag.mapper.common.EntityMapper views;

  private final vn.edu.utc.rag.persistence.repository.CrawlJobsRepository crawlJobsJpa;

  private Map<String, Object> findCrawlSourcesForCreate(Map<String, ?> parameters) {
    var result = crawlSourcesJpa.findLockedById((Long) views.value(parameters, "id", Long.class));
    var rows = result.stream().map(e -> views.project(e, "*")).toList();
    if (rows.isEmpty()) throw vn.edu.utc.rag.exception.ApiException.missing();
    return rows.getFirst();
  }

  private final vn.edu.utc.rag.persistence.repository.CrawlSourcesRepository crawlSourcesJpa;

  private List<Map<String, Object>> findCrawlJobsForCreate(Map<String, ?> parameters) {
    return crawlJobsJpa
        .findAllBySourceIdAndStatusIn(
            ((Number) parameters.get("id")).longValue(), List.of("PENDING", "RUNNING"))
        .stream()
        .map(e -> views.project(e, "id"))
        .toList();
  }

  private Map<String, Object> findCrawlJobsForCreate2(Map<String, ?> parameters) {
    var entity = new vn.edu.utc.rag.persistence.entity.CrawlJobsEntity();
    entity.setSourceId((Long) views.value(parameters, "s", Long.class));
    entity.setRequestedBy((UUID) views.value(parameters, "u", UUID.class));
    entity.setStatus("PENDING");
    entity.setRunAfter(java.time.Instant.now());
    entity.setAttemptNo(0);
    var saved = crawlJobsJpa.saveAndFlush(entity);
    return views.project(saved, "*");
  }
}
