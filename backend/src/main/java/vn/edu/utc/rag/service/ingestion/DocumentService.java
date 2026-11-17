package vn.edu.utc.rag.service.ingestion;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.springframework.web.multipart.MultipartFile;
import vn.edu.utc.rag.common.http.EntityTags;
import vn.edu.utc.rag.exception.ApiException;
import vn.edu.utc.rag.integration.pdf.PdfInspector;
import vn.edu.utc.rag.persistence.repository.query.DocumentQueryRepository;
import vn.edu.utc.rag.security.*;
import vn.edu.utc.rag.validation.Input;
import vn.edu.utc.rag.web.pagination.PageRequestFactory;

@Service
@Transactional()
public class DocumentService {
  private final PdfInspector pdfInspector;

  private final Permissions permissions;

  private final PageRequestFactory pages;

  private final RateLimits limits;

  private final Path uploads;

  private final String model;

  public DocumentService(
      PdfInspector pdfInspector,
      Permissions permissions,
      PageRequestFactory pages,
      RateLimits limits,
      @Value("${app.upload-directory}") String uploads,
      @Value("${app.ollama.embedding-model}") String model,
      DocumentQueryRepository repository,
      vn.edu.utc.rag.service.audit.AuditService audit,
      vn.edu.utc.rag.mapper.common.EntityMapper views,
      vn.edu.utc.rag.persistence.repository.DocumentRevisionsRepository documentRevisionsJpa,
      vn.edu.utc.rag.persistence.repository.CampusesRepository campusesJpa,
      vn.edu.utc.rag.persistence.repository.KnowledgeDocumentsRepository knowledgeDocumentsJpa,
      vn.edu.utc.rag.persistence.repository.DocumentChunksRepository documentChunksJpa) {
    this.pdfInspector = pdfInspector;
    this.permissions = permissions;
    this.pages = pages;
    this.limits = limits;
    this.uploads = Path.of(uploads).toAbsolutePath().normalize();
    this.model = model;
    this.repository = repository;
    this.audit = audit;
    this.views = views;
    this.documentRevisionsJpa = documentRevisionsJpa;
    this.campusesJpa = campusesJpa;
    this.knowledgeDocumentsJpa = knowledgeDocumentsJpa;
    this.documentChunksJpa = documentChunksJpa;
  }

  public Map<String, Object> revision(long id, HttpServletRequest req) {
    var row = findDocumentRevisionsForRevision(Map.of("id", id));
    permissions.check(req, row.get("campus_id"), "STAFF_EDITOR", "STAFF_REVIEWER");
    row.remove("storage_key");
    return row;
  }

  private void official(String url) {
    try {
      var u = URI.create(url);
      String host = u.getHost();
      if (!"https".equals(u.getScheme())
          || u.getUserInfo() != null
          || host == null
          || !(host.equals("utc.edu.vn")
              || host.endsWith(".utc.edu.vn")
              || host.equals("utc2.edu.vn")
              || host.endsWith(".utc2.edu.vn"))) throw new IllegalArgumentException();
    } catch (Exception e) {
      throw ApiException.invalid("Expected an official HTTPS UTC URL.");
    }
  }

  @Transactional
  public Object upload(
      MultipartFile file,
      String title,
      String url,
      String campusCode,
      Integer year,
      HttpServletRequest req) {
    Object campus =
        campusCode == null ? null : findCampusesForUpload(Map.of("code", campusCode)).get("id");
    var actor = permissions.check(req, campus, "STAFF_EDITOR");
    limits.hit("upload:" + actor, 10, java.time.Duration.ofMinutes(1));
    official(url);
    if (title == null
        || title.isBlank()
        || title.length() > 1000
        || year != null && (year < 1900 || year > 2200))
      throw ApiException.invalid("Invalid document metadata.");
    if (file.isEmpty() || file.getSize() > 20 * 1024 * 1024)
      throw new ApiException(413, "TOO_LARGE", "PDF must be at most 20 MiB.");
    Path target = uploads.resolve(UUID.randomUUID() + ".pdf");
    String hash;
    try {
      byte[] bytes = file.getBytes();
      pdfInspector.validate(bytes);
      hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
      Files.createDirectories(uploads);
      Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
    } catch (ApiException e) {
      throw e;
    } catch (Exception e) {
      throw new ApiException(415, "UNSUPPORTED_MEDIA", "Unable to read PDF.");
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {

          @Override
          public void afterCompletion(int status) {
            if (status != STATUS_COMMITTED)
              try {
                Files.deleteIfExists(target);
              } catch (Exception ignored) {
              }
          }
        });
    var doc = repository.findKnowledgeDocumentsForUpload(Map.of("url", url));
    var args = new HashMap<String, Object>();
    args.put("d", Input.id(doc.get("id")));
    args.put("u", actor);
    args.put("c", campus == null ? null : Input.id(campus));
    args.put("y", year);
    args.put("t", title);
    args.put("url", url);
    args.put("key", target.getFileName().toString());
    args.put("hash", hash);
    args.put("model", model);
    var revision = findDocumentRevisionsForUpload(args);
    revision.remove("storage_key");
    audit.record(actor, "UPLOAD", "document_revisions", revision.get("id"), Map.of());
    return revision;
  }

  public Object documents(HttpServletRequest req) {
    var scope = permissions.campusScope(req, "STAFF_EDITOR", "STAFF_REVIEWER");
    return repository.findPageForDocuments(Map.of(), pages.request(req), scope);
  }

  public Object revisions(long document, HttpServletRequest req) {
    vn.edu.utc.rag.persistence.query.CampusScope scope =
        permissions.campusScope(req, "STAFF_EDITOR", "STAFF_REVIEWER");
    var args = new HashMap<String, Object>(Map.of("d", document));
    repository.findRecordsForRevisions(args, scope);
    return repository.findPageForRevisions(args, pages.request(req), scope);
  }

  public Map<String, Object> publication(long document, HttpServletRequest req) {
    vn.edu.utc.rag.persistence.query.CampusScope scope =
        permissions.campusScope(req, "STAFF_EDITOR", "STAFF_REVIEWER");
    repository.findRecordsForPublication(Map.of("d", document), scope);
    var parent = findKnowledgeDocumentsForPublication(Map.of("id", document));
    var rows = repository.findRecordsForPublication2(Map.of("d", document), scope);
    var result = new LinkedHashMap<String, Object>();
    result.put("version_id", rows.isEmpty() ? null : rows.getFirst().get("version_id"));
    result.put("published_at", rows.isEmpty() ? null : rows.getFirst().get("published_at"));
    result.put("publication_version", parent.get("publication_version"));
    return result;
  }

  @Transactional
  public Map<String, Object> publish(
      long document, String expected, Map<String, Object> b, HttpServletRequest req) {
    Input.only(b, "version_id");
    if (!b.containsKey("version_id"))
      throw ApiException.invalid("version_id is required (or null).");
    var parent = findKnowledgeDocumentsForPublish(Map.of("id", document));
    var active = findDocumentRevisionsForPublish(Map.of("d", document));
    for (var a : active) permissions.check(req, a.get("campus_id"), "STAFF_REVIEWER");
    Map<String, Object> selected = null;
    UUID actor;
    if (b.get("version_id") != null) {
      selected =
          findDocumentRevisionsForPublish2(
              Map.of("v", Input.id(b.get("version_id")), "d", document));
      actor = permissions.check(req, selected.get("campus_id"), "STAFF_REVIEWER");
      official(selected.get("official_url").toString());
      if (selected.get("revoked_at") != null
          || !Set.of("PENDING", "PUBLISHED", "ARCHIVED").contains(selected.get("status")))
        throw ApiException.conflict("Document is not ready for verification.");
      var chunks = findDocumentChunksForPublish(Map.of("id", Input.id(selected.get("id"))));
      if (Long.parseLong(chunks.get("n").toString()) == 0
          || !model.equals(selected.get("embedding_model")))
        throw ApiException.conflict("No indexed chunks or incompatible embedding model.");
    } else {
      publication(document, req);
      vn.edu.utc.rag.persistence.query.CampusScope scope =
          permissions.campusScope(req, "STAFF_REVIEWER");
      var accessible = repository.findRecordsForPublish(Map.of("d", document), scope);
      actor = permissions.check(req, accessible.get("campus_id"), "STAFF_REVIEWER");
    }
    EntityTags.match(expected, parent.get("publication_version"), "publication");
    applyDocumentRevisionsForPublish(Map.of("d", document));
    if (selected != null)
      applyDocumentRevisionsForPublish2(Map.of("id", Input.id(selected.get("id")), "u", actor));
    applyKnowledgeDocumentsForPublish(Map.of("id", document));
    audit.record(actor, "DOCUMENT_PUBLICATION", "knowledge_documents", document, b);
    return publication(document, req);
  }

  @Transactional
  public Object revoke(long id, Map<String, Object> b, HttpServletRequest req) {
    Input.only(b, "reason");
    String reason = Input.text(b, "reason");
    var row = findDocumentRevisionsForRevoke(Map.of("id", id));
    var actor = permissions.check(req, row.get("campus_id"), "STAFF_REVIEWER");
    if (!Boolean.TRUE.equals(row.get("source_verified")))
      throw ApiException.conflict("Only verified sources may be revoked.");
    applyDocumentRevisionsForRevoke(Map.of("id", id, "reason", reason));
    applyKnowledgeDocumentsForRevoke(Map.of("id", Input.id(row.get("document_id"))));
    audit.record(actor, "REVOKE_SOURCE", "document_revisions", id, Map.of("reason", reason));
    return revision(id, req);
  }

  private final DocumentQueryRepository repository;

  private final vn.edu.utc.rag.service.audit.AuditService audit;

  private Map<String, Object> findDocumentRevisionsForRevision(Map<String, ?> parameters) {
    var result = documentRevisionsJpa.findById((Long) views.value(parameters, "id", Long.class));
    var rows = result.stream().map(e -> views.project(e, "*")).toList();
    if (rows.isEmpty()) throw vn.edu.utc.rag.exception.ApiException.missing();
    return rows.getFirst();
  }

  private final vn.edu.utc.rag.mapper.common.EntityMapper views;

  private final vn.edu.utc.rag.persistence.repository.DocumentRevisionsRepository
      documentRevisionsJpa;

  private Map<String, Object> findCampusesForUpload(Map<String, ?> parameters) {
    var result = campusesJpa.findAllByCode((String) views.value(parameters, "code", String.class));
    var rows = result.stream().map(e -> views.project(e, "id")).toList();
    if (rows.isEmpty()) throw vn.edu.utc.rag.exception.ApiException.missing();
    return rows.getFirst();
  }

  private final vn.edu.utc.rag.persistence.repository.CampusesRepository campusesJpa;

  private Map<String, Object> findDocumentRevisionsForUpload(Map<String, ?> parameters) {
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

  private Map<String, Object> findKnowledgeDocumentsForPublication(Map<String, ?> parameters) {
    var result = knowledgeDocumentsJpa.findById((Long) views.value(parameters, "id", Long.class));
    var rows = result.stream().map(e -> views.project(e, "publication_version")).toList();
    if (rows.isEmpty()) throw vn.edu.utc.rag.exception.ApiException.missing();
    return rows.getFirst();
  }

  private final vn.edu.utc.rag.persistence.repository.KnowledgeDocumentsRepository
      knowledgeDocumentsJpa;

  private Map<String, Object> findKnowledgeDocumentsForPublish(Map<String, ?> parameters) {
    var result =
        knowledgeDocumentsJpa.findLockedById((Long) views.value(parameters, "id", Long.class));
    var rows = result.stream().map(e -> views.project(e, "*")).toList();
    if (rows.isEmpty()) throw vn.edu.utc.rag.exception.ApiException.missing();
    return rows.getFirst();
  }

  private List<Map<String, Object>> findDocumentRevisionsForPublish(Map<String, ?> parameters) {
    var result =
        documentRevisionsJpa.findPublishedByDocumentId(
            (Long) views.value(parameters, "d", Long.class));
    var rows = result.stream().map(e -> views.project(e, "campus_id")).toList();
    return rows;
  }

  private Map<String, Object> findDocumentRevisionsForPublish2(Map<String, ?> parameters) {
    var result =
        documentRevisionsJpa.findAllByIdAndDocumentId(
            (Long) views.value(parameters, "v", Long.class),
            (Long) views.value(parameters, "d", Long.class));
    var rows = result.stream().map(e -> views.project(e, "*")).toList();
    if (rows.isEmpty()) throw vn.edu.utc.rag.exception.ApiException.missing();
    return rows.getFirst();
  }

  private Map<String, Object> findDocumentChunksForPublish(Map<String, ?> parameters) {
    var result =
        documentChunksJpa.countByRevisionId((Long) views.value(parameters, "id", Long.class));
    return java.util.Map.of("n", result.toString());
  }

  private final vn.edu.utc.rag.persistence.repository.DocumentChunksRepository documentChunksJpa;

  private int applyDocumentRevisionsForPublish(Map<String, ?> parameters) {
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

  private int applyDocumentRevisionsForPublish2(Map<String, ?> parameters) {
    var e =
        documentRevisionsJpa
            .findById(((Number) parameters.get("id")).longValue())
            .orElseThrow(vn.edu.utc.rag.exception.ApiException::missing);
    e.setStatus("PUBLISHED");
    e.setSourceVerified(true);
    e.setReviewedBy((UUID) parameters.get("u"));
    e.setPublishedAt(java.time.Instant.now());
    e.setUpdatedAt(java.time.Instant.now());
    e.setLockVersion(e.getLockVersion() + 1);
    return 1;
  }

  private int applyKnowledgeDocumentsForPublish(Map<String, ?> parameters) {
    var e =
        knowledgeDocumentsJpa
            .findById(((Number) parameters.get("id")).longValue())
            .orElseThrow(vn.edu.utc.rag.exception.ApiException::missing);
    e.setPublicationVersion(e.getPublicationVersion() + 1);
    return 1;
  }

  private Map<String, Object> findDocumentRevisionsForRevoke(Map<String, ?> parameters) {
    var result =
        documentRevisionsJpa.findLockedById((Long) views.value(parameters, "id", Long.class));
    var rows = result.stream().map(e -> views.project(e, "*")).toList();
    if (rows.isEmpty()) throw vn.edu.utc.rag.exception.ApiException.missing();
    return rows.getFirst();
  }

  private int applyDocumentRevisionsForRevoke(Map<String, ?> parameters) {
    var e =
        documentRevisionsJpa
            .findById(((Number) parameters.get("id")).longValue())
            .orElseThrow(vn.edu.utc.rag.exception.ApiException::missing);
    if (e.getRevokedAt() == null) e.setRevokedAt(java.time.Instant.now());
    e.setRevocationReason((String) parameters.get("reason"));
    e.setStatus("REVOKED");
    e.setUpdatedAt(java.time.Instant.now());
    e.setLockVersion(e.getLockVersion() + 1);
    return 1;
  }

  private int applyKnowledgeDocumentsForRevoke(Map<String, ?> parameters) {
    var e =
        knowledgeDocumentsJpa
            .findById(((Number) parameters.get("id")).longValue())
            .orElseThrow(vn.edu.utc.rag.exception.ApiException::missing);
    e.setPublicationVersion(e.getPublicationVersion() + 1);
    return 1;
  }
}
