package vn.edu.utc.rag.controller.ingestion;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import vn.edu.utc.rag.common.http.EntityTags;
import vn.edu.utc.rag.service.ingestion.DocumentService;
import vn.edu.utc.rag.service.ingestion.ProgramService;
import vn.edu.utc.rag.validation.Input;

@RestController
@RequestMapping("/api/v1/editorial")
public class DocumentsController {

  @PostMapping("/programs")
  ResponseEntity<?> program(@RequestBody Map<String, Object> b, HttpServletRequest req) {
    var row = service.program(b, req);
    return ResponseEntity.created(URI.create("/api/v1/editorial/programs/" + row.get("id")))
        .body(row);
  }

  @GetMapping("/programs/{id}")
  Object program(@PathVariable String id, HttpServletRequest req) {
    return service.program(id, req);
  }

  @GetMapping("/documents")
  Object documents(HttpServletRequest req) {
    return documents.documents(req);
  }

  @PostMapping(value = "/document-revisions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  ResponseEntity<?> upload(
      @RequestPart("file") MultipartFile file,
      @RequestParam String title,
      @RequestParam("official_url") String url,
      @RequestParam(value = "campus_code", required = false) String campus,
      @RequestParam(required = false) Integer year,
      HttpServletRequest req) {
    var row = (Map<?, ?>) documents.upload(file, title, url, campus, year, req);
    return ResponseEntity.accepted()
        .location(URI.create("/api/v1/editorial/document-revisions/" + row.get("id")))
        .body(row);
  }

  @GetMapping("/document-revisions/{id}")
  ResponseEntity<?> revision(@PathVariable String id, HttpServletRequest req) {
    var row = documents.revision(Input.id(id), req);
    return ResponseEntity.ok().eTag(EntityTags.etag(row.get("lock_version"), "version")).body(row);
  }

  @GetMapping("/documents/{id}/revisions")
  Object revisions(@PathVariable String id, HttpServletRequest req) {
    return documents.revisions(Input.id(id), req);
  }

  @GetMapping("/documents/{id}/publication")
  ResponseEntity<?> publication(@PathVariable String id, HttpServletRequest req) {
    return publicationResponse(documents.publication(Input.id(id), req));
  }

  @PutMapping("/documents/{id}/publication")
  ResponseEntity<?> publish(
      @PathVariable String id,
      @RequestHeader(value = "If-Match", required = false) String expected,
      @RequestBody Map<String, Object> b,
      HttpServletRequest req) {
    return publicationResponse(documents.publish(Input.id(id), expected, b, req));
  }

  @PutMapping("/document-revisions/{id}/revocation")
  Object revoke(
      @PathVariable String id, @RequestBody Map<String, Object> b, HttpServletRequest req) {
    return documents.revoke(Input.id(id), b, req);
  }

  private final ProgramService service;

  public DocumentsController(ProgramService service, DocumentService documents) {
    this.service = service;
    this.documents = documents;
  }

  private final DocumentService documents;

  private ResponseEntity<?> publicationResponse(Map<String, Object> row) {
    var version = row.remove("publication_version");
    return ResponseEntity.ok().eTag(EntityTags.etag(version, "publication")).body(row);
  }
}
