package vn.edu.utc.rag.persistence.repository.query;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.utc.rag.persistence.internal.NativeSqlExecutor;

@Repository
@Transactional
public class DocumentQueryRepository {

  private final NativeSqlExecutor query;

  public DocumentQueryRepository(
      NativeSqlExecutor query,
      vn.edu.utc.rag.mapper.common.EntityMapper views,
      vn.edu.utc.rag.persistence.repository.DocumentRevisionsRepository documentRevisionsJpa,
      vn.edu.utc.rag.persistence.query.QueryPages queryPages) {
    this.query = query;
    this.views = views;
    this.documentRevisionsJpa = documentRevisionsJpa;
    this.queryPages = queryPages;
  }

  public Map<String, Object> findKnowledgeDocumentsForUpload(Map<String, ?> parameters) {
    return query.one(
        "INSERT INTO knowledge_documents(canonical_url) VALUES(:url) ON CONFLICT(canonical_url)"
            + " DO UPDATE SET canonical_url=excluded.canonical_url RETURNING id",
        parameters);
  }

  public Map<String, Object> findRecordsForRevisions(
      Map<String, ?> parameters, vn.edu.utc.rag.persistence.query.CampusScope scope) {
    return visibleRevision(parameters, scope);
  }

  public Map<String, Object> findRecordsForPublication(
      Map<String, ?> parameters, vn.edu.utc.rag.persistence.query.CampusScope scope) {
    return visibleRevision(parameters, scope);
  }

  public List<Map<String, Object>> findRecordsForPublication2(
      Map<String, ?> parameters, vn.edu.utc.rag.persistence.query.CampusScope scope) {
    var spec =
        visibleDocument(((Number) parameters.get("d")).longValue(), scope)
            .and((r, q, b) -> b.equal(r.get("status"), "PUBLISHED"));
    return documentRevisionsJpa.findAll(spec).stream()
        .map(e -> views.project(e, "id AS version_id,published_at"))
        .toList();
  }

  public Map<String, Object> findRecordsForPublish(
      Map<String, ?> parameters, vn.edu.utc.rag.persistence.query.CampusScope scope) {
    return visibleRevision(parameters, scope);
  }

  private final vn.edu.utc.rag.mapper.common.EntityMapper views;

  private final vn.edu.utc.rag.persistence.repository.DocumentRevisionsRepository
      documentRevisionsJpa;

  private final vn.edu.utc.rag.persistence.query.QueryPages queryPages;

  public Map<String, Object> findPageForDocuments(
      Map<String, ?> input,
      vn.edu.utc.rag.persistence.query.QueryPage req,
      vn.edu.utc.rag.persistence.query.CampusScope scope) {
    Map<String, Object> parameters = new HashMap<>(input);
    String filters =
        vn.edu.utc.rag.persistence.query.QueryPages.filters(
            req, parameters, Map.of("year", "r.admission_year", "campus_code", "c.code"));
    if (req.getParameter("q") != null) {
      filters += " AND r.title ILIKE :q";
      parameters.put("q", "%" + req.getParameter("q") + "%");
    }
    return queryPages.page(
        "SELECT d.id,d.canonical_url FROM knowledge_documents d WHERE EXISTS(SELECT 1 FROM"
            + " document_revisions r LEFT JOIN campuses c ON c.id=r.campus_id WHERE"
            + " r.document_id=d.id AND "
            + scope.predicate("r.campus_id")
            + filters
            + ")",
        parameters,
        req,
        "id:bigint:asc");
  }

  public Map<String, Object> findPageForRevisions(
      Map<String, ?> input,
      vn.edu.utc.rag.persistence.query.QueryPage req,
      vn.edu.utc.rag.persistence.query.CampusScope scope) {
    Map<String, Object> parameters = new HashMap<>(input);
    return queryPages.page(
        "SELECT"
            + " r.id,r.document_id,r.title,r.official_url,r.campus_id,r.admission_year,r.status,r.source_verified,r.revoked_at,r.updated_at"
            + " FROM document_revisions r LEFT JOIN campuses c ON c.id=r.campus_id WHERE"
            + " r.document_id=:d AND "
            + scope.predicate("r.campus_id")
            + vn.edu.utc.rag.persistence.query.QueryPages.filters(
                req,
                parameters,
                Map.of("year", "r.admission_year", "campus_code", "c.code", "status", "r.status")),
        parameters,
        req,
        "id:bigint:asc");
  }

  private org.springframework.data.jpa.domain.Specification<
          vn.edu.utc.rag.persistence.entity.DocumentRevisionsEntity>
      visibleDocument(Long id, vn.edu.utc.rag.persistence.query.CampusScope scope) {
    return (r, q, b) ->
        b.and(
            b.equal(r.get("documentId"), id),
            scope.global() ? b.conjunction() : r.get("campusId").in(scope.ids()));
  }

  private Map<String, Object> visibleRevision(
      Map<String, ?> parameters, vn.edu.utc.rag.persistence.query.CampusScope scope) {
    var result =
        documentRevisionsJpa.findAll(
            visibleDocument(((Number) parameters.get("d")).longValue(), scope),
            org.springframework.data.domain.PageRequest.of(0, 1));
    return views.project(
        result.stream().findFirst().orElseThrow(vn.edu.utc.rag.exception.ApiException::missing),
        "id,campus_id");
  }
}
