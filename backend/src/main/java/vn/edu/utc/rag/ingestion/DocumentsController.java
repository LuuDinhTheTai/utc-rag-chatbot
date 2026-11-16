package vn.edu.utc.rag.ingestion;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.*;
import org.springframework.http.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import vn.edu.utc.rag.common.*;
import vn.edu.utc.rag.editorial.*;
import vn.edu.utc.rag.security.Permissions;

@RestController
@RequestMapping("/api/v1/editorial")
public class DocumentsController {
  private final DocumentService docs;
  private final Db db;
  private final Permissions permissions;
  private final SqlWrite write;

  public DocumentsController(DocumentService docs, Db db, Permissions permissions, SqlWrite write) {
    this.docs = docs;
    this.db = db;
    this.permissions = permissions;
    this.write = write;
  }

  @PostMapping("/programs")
  @Transactional
  ResponseEntity<?> program(@RequestBody Map<String, Object> b, HttpServletRequest req) {
    var actor = permissions.check(req, null, "STAFF_EDITOR");
    Input.only(b, "field_id", "parent_program_id", "code", "name");
    var row = write.insert("programs", write.fields(b, "field_id,code,name", "parent_program_id"));
    db.audit(actor, "CREATE", "programs", row.get("id"), Map.of());
    return ResponseEntity.created(URI.create("/api/v1/editorial/programs/" + row.get("id")))
        .body(row);
  }

  @GetMapping("/programs/{id}")
  Object program(@PathVariable String id, HttpServletRequest req) {
    permissions.check(req, null, "STAFF_EDITOR", "STAFF_REVIEWER");
    return db.one("SELECT * FROM programs WHERE id=:id", Map.of("id", Input.id(id)));
  }

  @GetMapping("/documents")
  Object documents(HttpServletRequest req) {
    return docs.documents(req);
  }

  @PostMapping(value = "/document-revisions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  ResponseEntity<?> upload(
      @RequestPart("file") MultipartFile file,
      @RequestParam String title,
      @RequestParam("official_url") String url,
      @RequestParam(value = "campus_code", required = false) String campus,
      @RequestParam(required = false) Integer year,
      HttpServletRequest req) {
    var row = (Map<?, ?>) docs.upload(file, title, url, campus, year, req);
    return ResponseEntity.accepted()
        .location(URI.create("/api/v1/editorial/document-revisions/" + row.get("id")))
        .body(row);
  }

  @GetMapping("/document-revisions/{id}")
  ResponseEntity<?> revision(@PathVariable String id, HttpServletRequest req) {
    var row = docs.revision(Input.id(id), req);
    return ResponseEntity.ok()
        .eTag(EditorialService.etag(row.get("lock_version"), "version"))
        .body(row);
  }

  @GetMapping("/documents/{id}/revisions")
  Object revisions(@PathVariable String id, HttpServletRequest req) {
    return docs.revisions(Input.id(id), req);
  }

  @GetMapping("/documents/{id}/publication")
  ResponseEntity<?> publication(@PathVariable String id, HttpServletRequest req) {
    return publication(docs.publication(Input.id(id), req));
  }

  @PutMapping("/documents/{id}/publication")
  ResponseEntity<?> publish(
      @PathVariable String id,
      @RequestHeader(value = "If-Match", required = false) String expected,
      @RequestBody Map<String, Object> b,
      HttpServletRequest req) {
    return publication(docs.publish(Input.id(id), expected, b, req));
  }

  private ResponseEntity<?> publication(Map<String, Object> row) {
    var version = row.remove("publication_version");
    return ResponseEntity.ok().eTag(EditorialService.etag(version, "publication")).body(row);
  }

  @PutMapping("/document-revisions/{id}/revocation")
  Object revoke(
      @PathVariable String id, @RequestBody Map<String, Object> b, HttpServletRequest req) {
    return docs.revoke(Input.id(id), b, req);
  }
}
