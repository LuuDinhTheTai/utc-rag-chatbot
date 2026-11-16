package vn.edu.utc.rag.ingestion;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.springframework.web.multipart.MultipartFile;
import vn.edu.utc.rag.common.*;
import vn.edu.utc.rag.editorial.EditorialService;
import vn.edu.utc.rag.security.*;

@Service
public class DocumentService {
  private final Db db;
  private final Permissions permissions;
  private final Pages pages;
  private final RateLimits limits;
  private final Path uploads;
  private final String model;

  public DocumentService(
      Db db,
      Permissions permissions,
      Pages pages,
      RateLimits limits,
      @Value("${app.upload-directory}") String uploads,
      @Value("${app.ollama.embedding-model}") String model) {
    this.db = db;
    this.permissions = permissions;
    this.pages = pages;
    this.limits = limits;
    this.uploads = Path.of(uploads).toAbsolutePath().normalize();
    this.model = model;
  }

  public Map<String, Object> revision(long id, HttpServletRequest req) {
    var row = db.one("SELECT * FROM document_revisions WHERE id=:id", Map.of("id", id));
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
        campusCode == null
            ? null
            : db.one("SELECT id FROM campuses WHERE code=:code", Map.of("code", campusCode))
                .get("id");
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
      if (bytes.length < 5
          || !new String(bytes, 0, 5, java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-"))
        throw new ApiException(415, "UNSUPPORTED_MEDIA", "Expected PDF.");
      try (var pdf = Loader.loadPDF(bytes)) {
        if (pdf.isEncrypted() || pdf.getNumberOfPages() < 1)
          throw new ApiException(
              415, "UNSUPPORTED_MEDIA", "Encrypted or empty PDF is not supported.");
      }
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
    var doc =
        db.one(
            "INSERT INTO knowledge_documents(canonical_url) VALUES(:url) ON CONFLICT(canonical_url)"
                + " DO UPDATE SET canonical_url=excluded.canonical_url RETURNING id",
            Map.of("url", url));
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
    var revision =
        db.one(
            "INSERT INTO"
                + " document_revisions(document_id,uploaded_by,campus_id,admission_year,title,official_url,storage_key,content_hash,pipeline_key,embedding_model,status,updated_at)"
                + " VALUES(:d,:u,:c,:y,:t,:url,:key,:hash,'pdf-v1',:model,'INDEXING',now())"
                + " RETURNING *",
            args);
    revision.remove("storage_key");
    db.audit(actor, "UPLOAD", "document_revisions", revision.get("id"), Map.of());
    return revision;
  }

  public Object documents(HttpServletRequest req) {
    String scope = permissions.campusFilter(req, "r.campus_id", "STAFF_EDITOR", "STAFF_REVIEWER");
    var args = new HashMap<String, Object>();
    String filters =
        Pages.filters(req, args, Map.of("year", "r.admission_year", "campus_code", "c.code"));
    if (req.getParameter("q") != null) {
      filters += " AND r.title ILIKE :q";
      args.put("q", "%" + req.getParameter("q") + "%");
    }
    return pages.page(
        "SELECT d.id,d.canonical_url FROM knowledge_documents d WHERE EXISTS(SELECT 1 FROM"
            + " document_revisions r LEFT JOIN campuses c ON c.id=r.campus_id WHERE"
            + " r.document_id=d.id AND "
            + scope
            + filters
            + ")",
        args,
        req,
        "id:bigint:asc");
  }

  public Object revisions(long document, HttpServletRequest req) {
    String scope = permissions.campusFilter(req, "r.campus_id", "STAFF_EDITOR", "STAFF_REVIEWER");
    var args = new HashMap<String, Object>(Map.of("d", document));
    db.one(
        "SELECT id FROM document_revisions r WHERE document_id=:d AND " + scope + " LIMIT 1", args);
    return pages.page(
        "SELECT"
            + " r.id,r.document_id,r.title,r.official_url,r.campus_id,r.admission_year,r.status,r.source_verified,r.revoked_at,r.updated_at"
            + " FROM document_revisions r LEFT JOIN campuses c ON c.id=r.campus_id WHERE"
            + " r.document_id=:d AND "
            + scope
            + Pages.filters(
                req,
                args,
                Map.of("year", "r.admission_year", "campus_code", "c.code", "status", "r.status")),
        args,
        req,
        "id:bigint:asc");
  }

  public Map<String, Object> publication(long document, HttpServletRequest req) {
    String scope = permissions.campusFilter(req, "r.campus_id", "STAFF_EDITOR", "STAFF_REVIEWER");
    db.one(
        "SELECT id FROM document_revisions r WHERE document_id=:d AND " + scope + " LIMIT 1",
        Map.of("d", document));
    var parent =
        db.one(
            "SELECT publication_version FROM knowledge_documents WHERE id=:id",
            Map.of("id", document));
    var rows =
        db.list(
            "SELECT r.id AS version_id,r.published_at FROM document_revisions r WHERE"
                + " document_id=:d AND status='PUBLISHED' AND "
                + scope,
            Map.of("d", document));
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
    var parent =
        db.one("SELECT * FROM knowledge_documents WHERE id=:id FOR UPDATE", Map.of("id", document));
    var active =
        db.list(
            "SELECT campus_id FROM document_revisions WHERE document_id=:d AND status='PUBLISHED'",
            Map.of("d", document));
    for (var a : active) permissions.check(req, a.get("campus_id"), "STAFF_REVIEWER");
    Map<String, Object> selected = null;
    UUID actor;
    if (b.get("version_id") != null) {
      selected =
          db.one(
              "SELECT * FROM document_revisions WHERE id=:v AND document_id=:d FOR UPDATE",
              Map.of("v", Input.id(b.get("version_id")), "d", document));
      actor = permissions.check(req, selected.get("campus_id"), "STAFF_REVIEWER");
      official(selected.get("official_url").toString());
      if (selected.get("revoked_at") != null
          || !Set.of("PENDING", "PUBLISHED", "ARCHIVED").contains(selected.get("status")))
        throw ApiException.conflict("Document is not ready for verification.");
      var chunks =
          db.one(
              "SELECT count(*) AS n FROM document_chunks WHERE revision_id=:id",
              Map.of("id", Input.id(selected.get("id"))));
      if (Long.parseLong(chunks.get("n").toString()) == 0
          || !model.equals(selected.get("embedding_model")))
        throw ApiException.conflict("No indexed chunks or incompatible embedding model.");
    } else {
      publication(document, req);
      String scope = permissions.campusFilter(req, "r.campus_id", "STAFF_REVIEWER");
      var accessible =
          db.one(
              "SELECT campus_id FROM document_revisions r WHERE document_id=:d AND "
                  + scope
                  + " LIMIT 1",
              Map.of("d", document));
      actor = permissions.check(req, accessible.get("campus_id"), "STAFF_REVIEWER");
    }
    EditorialService.match(expected, parent.get("publication_version"), "publication");
    db.update(
        "UPDATE document_revisions SET"
            + " status='ARCHIVED',lock_version=lock_version+1,updated_at=now() WHERE document_id=:d"
            + " AND status='PUBLISHED'",
        Map.of("d", document));
    if (selected != null)
      db.update(
          "UPDATE document_revisions SET"
              + " status='PUBLISHED',source_verified=true,reviewed_by=:u,published_at=now(),updated_at=now(),lock_version=lock_version+1"
              + " WHERE id=:id",
          Map.of("id", Input.id(selected.get("id")), "u", actor));
    db.update(
        "UPDATE knowledge_documents SET publication_version=publication_version+1 WHERE id=:id",
        Map.of("id", document));
    db.audit(actor, "DOCUMENT_PUBLICATION", "knowledge_documents", document, b);
    return publication(document, req);
  }

  @Transactional
  public Object revoke(long id, Map<String, Object> b, HttpServletRequest req) {
    Input.only(b, "reason");
    String reason = Input.text(b, "reason");
    var row = db.one("SELECT * FROM document_revisions WHERE id=:id FOR UPDATE", Map.of("id", id));
    var actor = permissions.check(req, row.get("campus_id"), "STAFF_REVIEWER");
    if (!Boolean.TRUE.equals(row.get("source_verified")))
      throw ApiException.conflict("Only verified sources may be revoked.");
    db.update(
        "UPDATE document_revisions SET"
            + " revoked_at=coalesce(revoked_at,now()),revocation_reason=:reason,status='REVOKED',updated_at=now(),lock_version=lock_version+1"
            + " WHERE id=:id",
        Map.of("id", id, "reason", reason));
    db.update(
        "UPDATE knowledge_documents SET publication_version=publication_version+1 WHERE id=:id",
        Map.of("id", Input.id(row.get("document_id"))));
    db.audit(actor, "REVOKE_SOURCE", "document_revisions", id, Map.of("reason", reason));
    return revision(id, req);
  }
}
