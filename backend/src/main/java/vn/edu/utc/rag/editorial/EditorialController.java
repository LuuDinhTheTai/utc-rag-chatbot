package vn.edu.utc.rag.editorial;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import vn.edu.utc.rag.common.Input;

@RestController
@RequestMapping(
    "/api/v1/editorial/{kind:admission-cycles|program-offerings|guide-items|financial-policies|score-rules}")
public class EditorialController {
  private final EditorialService service;

  public EditorialController(EditorialService service) {
    this.service = service;
  }

  @GetMapping
  Object list(@PathVariable String kind, HttpServletRequest req) {
    return service.list(kind, req);
  }

  @PostMapping
  ResponseEntity<?> create(
      @PathVariable String kind, @RequestBody Map<String, Object> body, HttpServletRequest req) {
    var r = service.create(kind, body, req);
    return ResponseEntity.created(URI.create(req.getRequestURI() + "/" + r.get("id"))).body(r);
  }

  @GetMapping("/{id}")
  Object item(@PathVariable String kind, @PathVariable String id, HttpServletRequest req) {
    return service.item(kind, Input.id(id), req, false, "STAFF_EDITOR", "STAFF_REVIEWER");
  }

  @GetMapping("/{id}/versions")
  Object versions(@PathVariable String kind, @PathVariable String id, HttpServletRequest req) {
    return service.versions(kind, Input.id(id), req);
  }

  @PostMapping("/{id}/versions")
  ResponseEntity<?> draft(
      @PathVariable String kind,
      @PathVariable String id,
      @RequestBody Map<String, Object> body,
      HttpServletRequest req) {
    var r = service.save(kind, Input.id(id), null, null, body, req);
    return ResponseEntity.created(URI.create(req.getRequestURI() + "/" + r.get("id")))
        .eTag(EditorialService.etag(r.get("lock_version"), "version"))
        .body(r);
  }

  @GetMapping("/{id}/versions/{version}")
  ResponseEntity<?> version(
      @PathVariable String kind,
      @PathVariable String id,
      @PathVariable String version,
      HttpServletRequest req) {
    var r = service.version(kind, Input.id(id), Input.id(version), req);
    return ResponseEntity.ok()
        .eTag(EditorialService.etag(r.get("lock_version"), "version"))
        .body(r);
  }

  @PutMapping("/{id}/versions/{version}")
  ResponseEntity<?> save(
      @PathVariable String kind,
      @PathVariable String id,
      @PathVariable String version,
      @RequestHeader(value = "If-Match", required = false) String expected,
      @RequestBody Map<String, Object> body,
      HttpServletRequest req) {
    var r = service.save(kind, Input.id(id), Input.id(version), expected, body, req);
    return ResponseEntity.ok()
        .eTag(EditorialService.etag(r.get("lock_version"), "version"))
        .body(r);
  }

  @GetMapping("/{id}/publication")
  ResponseEntity<?> publication(
      @PathVariable String kind, @PathVariable String id, HttpServletRequest req) {
    return publicationResponse(service.publication(kind, Input.id(id), req));
  }

  @PutMapping("/{id}/publication")
  ResponseEntity<?> publish(
      @PathVariable String kind,
      @PathVariable String id,
      @RequestHeader(value = "If-Match", required = false) String expected,
      @RequestBody Map<String, Object> body,
      HttpServletRequest req) {
    return publicationResponse(service.publish(kind, Input.id(id), expected, body, req));
  }

  private ResponseEntity<?> publicationResponse(Map<String, Object> row) {
    Object etag = row.remove("publication_version");
    return ResponseEntity.ok().eTag(EditorialService.etag(etag, "publication")).body(row);
  }
}
