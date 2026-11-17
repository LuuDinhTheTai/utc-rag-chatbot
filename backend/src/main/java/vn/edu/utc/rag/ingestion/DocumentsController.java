package vn.edu.utc.rag.ingestion;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import vn.edu.utc.rag.common.*;
import vn.edu.utc.rag.editorial.*;

@RestController
@RequestMapping("/api/v1/editorial")
public class DocumentsController {

  @PostMapping("/programs")
  ResponseEntity<?> program(@RequestBody Map<String, Object> b, HttpServletRequest req) {
    return service.program(b, req);
  }

  @GetMapping("/programs/{id}")
  Object program(@PathVariable String id, HttpServletRequest req) {
    return service.program(id, req);
  }

  @GetMapping("/documents")
  Object documents(HttpServletRequest req) {
    return service.documents(req);
  }

  @PostMapping(value = "/document-revisions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  ResponseEntity<?> upload(
      @RequestPart("file") MultipartFile file,
      @RequestParam String title,
      @RequestParam("official_url") String url,
      @RequestParam(value = "campus_code", required = false) String campus,
      @RequestParam(required = false) Integer year,
      HttpServletRequest req) {
    return service.upload(file, title, url, campus, year, req);
  }

  @GetMapping("/document-revisions/{id}")
  ResponseEntity<?> revision(@PathVariable String id, HttpServletRequest req) {
    return service.revision(id, req);
  }

  @GetMapping("/documents/{id}/revisions")
  Object revisions(@PathVariable String id, HttpServletRequest req) {
    return service.revisions(id, req);
  }

  @GetMapping("/documents/{id}/publication")
  ResponseEntity<?> publication(@PathVariable String id, HttpServletRequest req) {
    return service.publication(id, req);
  }

  @PutMapping("/documents/{id}/publication")
  ResponseEntity<?> publish(
      @PathVariable String id,
      @RequestHeader(value = "If-Match", required = false) String expected,
      @RequestBody Map<String, Object> b,
      HttpServletRequest req) {
    return service.publish(id, expected, b, req);
  }

  @PutMapping("/document-revisions/{id}/revocation")
  Object revoke(
      @PathVariable String id, @RequestBody Map<String, Object> b, HttpServletRequest req) {
    return service.revoke(id, b, req);
  }

  private final DocumentApiService service;

  public DocumentsController(DocumentApiService service) {
    this.service = service;
  }
}
