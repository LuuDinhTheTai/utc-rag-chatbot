package vn.edu.utc.rag.ingestion;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.utc.rag.persistence.internal.NativeSqlExecutor;

@Repository
@Transactional
public class DocumentDataRepository {

  private final NativeSqlExecutor query;

  public DocumentDataRepository(
      NativeSqlExecutor query,
      vn.edu.utc.rag.persistence.internal.EntityViews views,
      vn.edu.utc.rag.persistence.repository.CampusesRepository campusesJpa,
      vn.edu.utc.rag.persistence.repository.DocumentChunksRepository documentChunksJpa,
      vn.edu.utc.rag.persistence.repository.DocumentRevisionsRepository documentRevisionsJpa,
      vn.edu.utc.rag.persistence.repository.KnowledgeDocumentsRepository knowledgeDocumentsJpa,
      vn.edu.utc.rag.persistence.query.QueryPages queryPages) {
    this.query = query;
    this.views = views;
    this.campusesJpa = campusesJpa;
    this.documentChunksJpa = documentChunksJpa;
    this.documentRevisionsJpa = documentRevisionsJpa;
    this.knowledgeDocumentsJpa = knowledgeDocumentsJpa;
    this.queryPages = queryPages;
  }

  public Map<String, Object> findDocumentRevisionsForRevision(Map<String, ?> parameters) {
    var result = documentRevisionsJpa.findById((Long) views.value(parameters, "id", Long.class));
    var rows = result.stream().map(e -> views.project(e, "*")).toList();
    if (rows.isEmpty()) throw vn.edu.utc.rag.common.ApiException.missing();
    return rows.getFirst();
  }

  public Map<String, Object> findCampusesForUpload(Map<String, ?> parameters) {
    var result = campusesJpa.findAllByCode((String) views.value(parameters, "code", String.class));
    var rows = result.stream().map(e -> views.project(e, "id")).toList();
    if (rows.isEmpty()) throw vn.edu.utc.rag.common.ApiException.missing();
    return rows.getFirst();
  }

  public Map<String, Object> findKnowledgeDocumentsForUpload(Map<String, ?> parameters) {
    return query.one(
        "INSERT INTO knowledge_documents(canonical_url) VALUES(:url) ON CONFLICT(canonical_url)"
            + " DO UPDATE SET canonical_url=excluded.canonical_url RETURNING id",
        parameters);
  }

  public Map<String, Object> findDocumentRevisionsForUpload(Map<String, ?> parameters) {
    var entity = new vn.edu.utc.rag.persistence.entity.DocumentRevisionsEntity();
    entity.setDocumentId((Long) views.value(parameters, "d", Long.class));
    entity.setUploadedBy((UUID) views.value(parameters, "u", UUID.class));
    entity.setCampusId((Short) views.value(parameters, "c", Short.class));
    entity.setAdmissionYear((Short) views.value(parameters, "y", Short.class));
    entity.setTitle((String) views.value(parameters, "t", String.class));
    entity.setOfficialUrl((String) views.value(parameters, "url", String.class));
    entity.setStorageKey((String) views.value(parameters, "key", String.class));
    entity.setContentHash((String) views.value(parameters, "hash", String.class));
    entity.setPipelineKey("pdf-v1");
    entity.setEmbeddingModel((String) views.value(parameters, "model", String.class));
    entity.setStatus("INDEXING");
    entity.setUpdatedAt(java.time.Instant.now());
    var saved = documentRevisionsJpa.saveAndFlush(entity);
    return views.project(saved, "*");
  }

  public Map<String, Object> findRecordsForRevisions(
      Map<String, ?> parameters, vn.edu.utc.rag.persistence.query.CampusScope scope) {
    return visibleRevision(parameters, scope);
  }

  public Map<String, Object> findRecordsForPublication(
      Map<String, ?> parameters, vn.edu.utc.rag.persistence.query.CampusScope scope) {
    return visibleRevision(parameters, scope);
  }

  public Map<String, Object> findKnowledgeDocumentsForPublication(Map<String, ?> parameters) {
    var result = knowledgeDocumentsJpa.findById((Long) views.value(parameters, "id", Long.class));
    var rows = result.stream().map(e -> views.project(e, "publication_version")).toList();
    if (rows.isEmpty()) throw vn.edu.utc.rag.common.ApiException.missing();
    return rows.getFirst();
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

  public Map<String, Object> findKnowledgeDocumentsForPublish(Map<String, ?> parameters) {
    var result =
        knowledgeDocumentsJpa.findLockedById((Long) views.value(parameters, "id", Long.class));
    var rows = result.stream().map(e -> views.project(e, "*")).toList();
    if (rows.isEmpty()) throw vn.edu.utc.rag.common.ApiException.missing();
    return rows.getFirst();
  }

  public List<Map<String, Object>> findDocumentRevisionsForPublish(Map<String, ?> parameters) {
    var result =
        documentRevisionsJpa.findPublishedByDocumentId(
            (Long) views.value(parameters, "d", Long.class));
    var rows = result.stream().map(e -> views.project(e, "campus_id")).toList();
    return rows;
  }

  public Map<String, Object> findDocumentRevisionsForPublish2(Map<String, ?> parameters) {
    var result =
        documentRevisionsJpa.findAllByIdAndDocumentId(
            (Long) views.value(parameters, "v", Long.class),
            (Long) views.value(parameters, "d", Long.class));
    var rows = result.stream().map(e -> views.project(e, "*")).toList();
    if (rows.isEmpty()) throw vn.edu.utc.rag.common.ApiException.missing();
    return rows.getFirst();
  }

  public Map<String, Object> findDocumentChunksForPublish(Map<String, ?> parameters) {
    var result =
        documentChunksJpa.countByRevisionId((Long) views.value(parameters, "id", Long.class));
    return java.util.Map.of("n", result.toString());
  }

  public Map<String, Object> findRecordsForPublish(
      Map<String, ?> parameters, vn.edu.utc.rag.persistence.query.CampusScope scope) {
    return visibleRevision(parameters, scope);
  }

  public int applyDocumentRevisionsForPublish(Map<String, ?> parameters) {
    for (var e :
        documentRevisionsJpa.findAllByDocumentIdAndStatus(
            ((Number) parameters.get("d")).longValue(), "PUBLISHED")) {
      e.setStatus("ARCHIVED");
      e.setLockVersion(e.getLockVersion() + 1);
      e.setUpdatedAt(java.time.Instant.now());
    }
    documentRevisionsJpa.flush();
    return 1;
  }

  public int applyDocumentRevisionsForPublish2(Map<String, ?> parameters) {
    var e =
        documentRevisionsJpa
            .findById(((Number) parameters.get("id")).longValue())
            .orElseThrow(vn.edu.utc.rag.common.ApiException::missing);
    e.setStatus("PUBLISHED");
    e.setSourceVerified(true);
    e.setReviewedBy((UUID) parameters.get("u"));
    e.setPublishedAt(java.time.Instant.now());
    e.setUpdatedAt(java.time.Instant.now());
    e.setLockVersion(e.getLockVersion() + 1);
    return 1;
  }

  public int applyKnowledgeDocumentsForPublish(Map<String, ?> parameters) {
    var e =
        knowledgeDocumentsJpa
            .findById(((Number) parameters.get("id")).longValue())
            .orElseThrow(vn.edu.utc.rag.common.ApiException::missing);
    e.setPublicationVersion(e.getPublicationVersion() + 1);
    return 1;
  }

  public Map<String, Object> findDocumentRevisionsForRevoke(Map<String, ?> parameters) {
    var result =
        documentRevisionsJpa.findLockedById((Long) views.value(parameters, "id", Long.class));
    var rows = result.stream().map(e -> views.project(e, "*")).toList();
    if (rows.isEmpty()) throw vn.edu.utc.rag.common.ApiException.missing();
    return rows.getFirst();
  }

  public int applyDocumentRevisionsForRevoke(Map<String, ?> parameters) {
    var e =
        documentRevisionsJpa
            .findById(((Number) parameters.get("id")).longValue())
            .orElseThrow(vn.edu.utc.rag.common.ApiException::missing);
    if (e.getRevokedAt() == null) e.setRevokedAt(java.time.Instant.now());
    e.setRevocationReason((String) parameters.get("reason"));
    e.setStatus("REVOKED");
    e.setUpdatedAt(java.time.Instant.now());
    e.setLockVersion(e.getLockVersion() + 1);
    return 1;
  }

  public int applyKnowledgeDocumentsForRevoke(Map<String, ?> parameters) {
    var e =
        knowledgeDocumentsJpa
            .findById(((Number) parameters.get("id")).longValue())
            .orElseThrow(vn.edu.utc.rag.common.ApiException::missing);
    e.setPublicationVersion(e.getPublicationVersion() + 1);
    return 1;
  }

  private final vn.edu.utc.rag.persistence.internal.EntityViews views;

  private final vn.edu.utc.rag.persistence.repository.CampusesRepository campusesJpa;

  private final vn.edu.utc.rag.persistence.repository.DocumentChunksRepository documentChunksJpa;

  private final vn.edu.utc.rag.persistence.repository.DocumentRevisionsRepository
      documentRevisionsJpa;

  private final vn.edu.utc.rag.persistence.repository.KnowledgeDocumentsRepository
      knowledgeDocumentsJpa;

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
        result.stream().findFirst().orElseThrow(vn.edu.utc.rag.common.ApiException::missing),
        "id,campus_id");
  }
}
