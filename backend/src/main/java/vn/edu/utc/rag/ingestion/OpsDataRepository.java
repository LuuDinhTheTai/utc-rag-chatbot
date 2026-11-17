package vn.edu.utc.rag.ingestion;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional
public class OpsDataRepository {

  public OpsDataRepository(
      vn.edu.utc.rag.persistence.internal.EntityViews views,
      vn.edu.utc.rag.persistence.repository.CrawlJobsRepository crawlJobsJpa,
      vn.edu.utc.rag.persistence.repository.CrawlSourcesRepository crawlSourcesJpa,
      vn.edu.utc.rag.persistence.query.QueryPages queryPages) {
    this.views = views;
    this.crawlJobsJpa = crawlJobsJpa;
    this.crawlSourcesJpa = crawlSourcesJpa;
    this.queryPages = queryPages;
  }

  public Map<String, Object> findCrawlJobsForJob(Map<String, ?> parameters) {
    var result = crawlJobsJpa.findById((Long) views.value(parameters, "id", Long.class));
    var rows = result.stream().map(e -> views.project(e, "*")).toList();
    if (rows.isEmpty()) throw vn.edu.utc.rag.common.ApiException.missing();
    return rows.getFirst();
  }

  public Map<String, Object> findCrawlSourcesForCreate(Map<String, ?> parameters) {
    var result = crawlSourcesJpa.findLockedById((Long) views.value(parameters, "id", Long.class));
    var rows = result.stream().map(e -> views.project(e, "*")).toList();
    if (rows.isEmpty()) throw vn.edu.utc.rag.common.ApiException.missing();
    return rows.getFirst();
  }

  public List<Map<String, Object>> findCrawlJobsForCreate(Map<String, ?> parameters) {
    return crawlJobsJpa
        .findAllBySourceIdAndStatusIn(
            ((Number) parameters.get("id")).longValue(), List.of("PENDING", "RUNNING"))
        .stream()
        .map(e -> views.project(e, "id"))
        .toList();
  }

  public Map<String, Object> findCrawlJobsForCreate2(Map<String, ?> parameters) {
    var entity = new vn.edu.utc.rag.persistence.entity.CrawlJobsEntity();
    entity.setSourceId((Long) views.value(parameters, "s", Long.class));
    entity.setRequestedBy((UUID) views.value(parameters, "u", UUID.class));
    entity.setStatus("PENDING");
    entity.setRunAfter(java.time.Instant.now());
    entity.setAttemptNo(0);
    var saved = crawlJobsJpa.saveAndFlush(entity);
    return views.project(saved, "*");
  }

  private final vn.edu.utc.rag.persistence.internal.EntityViews views;

  private final vn.edu.utc.rag.persistence.repository.CrawlJobsRepository crawlJobsJpa;

  private final vn.edu.utc.rag.persistence.repository.CrawlSourcesRepository crawlSourcesJpa;

  private final vn.edu.utc.rag.persistence.query.QueryPages queryPages;

  public Map<String, Object> findPageForSources(
      Map<String, ?> input, vn.edu.utc.rag.persistence.query.QueryPage req) {
    Map<String, Object> parameters = new HashMap<>(input);
    return queryPages.page("SELECT * FROM crawl_sources", parameters, req, "id:bigint:asc");
  }

  public Map<String, Object> findPageForJobs(
      Map<String, ?> input, vn.edu.utc.rag.persistence.query.QueryPage req) {
    Map<String, Object> parameters = new HashMap<>(input);
    return queryPages.page(
        "SELECT * FROM crawl_jobs WHERE true"
            + vn.edu.utc.rag.persistence.query.QueryPages.filters(
                req, parameters, Map.of("source_id", "source_id", "status", "status")),
        parameters,
        req,
        "id:bigint:desc");
  }
}
