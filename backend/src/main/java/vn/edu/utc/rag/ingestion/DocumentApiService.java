package vn.edu.utc.rag.ingestion;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.*;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import vn.edu.utc.rag.common.*;
import vn.edu.utc.rag.editorial.*;
import vn.edu.utc.rag.security.Permissions;

@Service()
public class DocumentApiService {

  private final DocumentService docs;

  private final Permissions permissions;

  private final DraftFields write;

  public DocumentApiService(
      DocumentService docs,
      Permissions permissions,
      DraftFields write,
      DocumentsDataRepository repository,
      vn.edu.utc.rag.persistence.repository.AuditRepository audit) {
    this.docs = docs;
    this.permissions = permissions;
    this.write = write;
    this.repository = repository;
    this.audit = audit;
  }

  @Transactional
  public ResponseEntity<?> program(Map<String, Object> b, HttpServletRequest req) {
    var actor = permissions.check(req, null, "STAFF_EDITOR");
    Input.only(b, "field_id", "parent_program_id", "code", "name");
    var row = repository.createProgram(write.fields(b, "field_id,code,name", "parent_program_id"));
    audit.record(actor, "CREATE", "programs", row.get("id"), Map.of());
    return ResponseEntity.created(URI.create("/api/v1/editorial/programs/" + row.get("id")))
        .body(row);
  }

  public Object program(String id, HttpServletRequest req) {
    permissions.check(req, null, "STAFF_EDITOR", "STAFF_REVIEWER");
    return repository.findProgramsForProgram(Map.of("id", Input.id(id)));
  }

  public Object documents(HttpServletRequest req) {
    return docs.documents(req);
  }

  public ResponseEntity<?> upload(
      MultipartFile file,
      String title,
      String url,
      String campus,
      Integer year,
      HttpServletRequest req) {
    var row = (Map<?, ?>) docs.upload(file, title, url, campus, year, req);
    return ResponseEntity.accepted()
        .location(URI.create("/api/v1/editorial/document-revisions/" + row.get("id")))
        .body(row);
  }

  public ResponseEntity<?> revision(String id, HttpServletRequest req) {
    var row = docs.revision(Input.id(id), req);
    return ResponseEntity.ok()
        .eTag(EditorialService.etag(row.get("lock_version"), "version"))
        .body(row);
  }

  public Object revisions(String id, HttpServletRequest req) {
    return docs.revisions(Input.id(id), req);
  }

  public ResponseEntity<?> publication(String id, HttpServletRequest req) {
    return publication(docs.publication(Input.id(id), req));
  }

  public ResponseEntity<?> publish(
      String id, String expected, Map<String, Object> b, HttpServletRequest req) {
    return publication(docs.publish(Input.id(id), expected, b, req));
  }

  private ResponseEntity<?> publication(Map<String, Object> row) {
    var version = row.remove("publication_version");
    return ResponseEntity.ok().eTag(EditorialService.etag(version, "publication")).body(row);
  }

  public Object revoke(String id, Map<String, Object> b, HttpServletRequest req) {
    return docs.revoke(Input.id(id), b, req);
  }

  private final DocumentsDataRepository repository;

  private final vn.edu.utc.rag.persistence.repository.AuditRepository audit;
}
