package vn.edu.utc.rag.personality;

import jakarta.servlet.http.HttpServletRequest;
import java.io.*;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import vn.edu.utc.rag.catalog.CatalogController;
import vn.edu.utc.rag.common.*;
import vn.edu.utc.rag.security.Access;

@RestController
@RequestMapping("/api/v1")
public class PersonalityController {
  private final PersonalityService service;
  private final CatalogController catalog;
  private final Access access;

  public PersonalityController(
      PersonalityService service, CatalogController catalog, Access access) {
    this.service = service;
    this.catalog = catalog;
    this.access = access;
  }

  @PostMapping("/personality-attempts")
  ResponseEntity<?> create(@RequestBody Map<String, Object> b, HttpServletRequest req) {
    var r = (Map<?, ?>) service.create(b, req);
    return ResponseEntity.created(URI.create("/api/v1/personality-attempts/" + r.get("id")))
        .body(r);
  }

  @GetMapping("/personality-attempts/{id}")
  Object get(@PathVariable String id, HttpServletRequest r) {
    return service.clean(service.own(Input.uuid(id), r));
  }

  @GetMapping("/personality-attempts/{id}/questions")
  Object questions(@PathVariable String id, HttpServletRequest r) {
    var a = service.own(Input.uuid(id), r);
    return catalog.questionsForVersion(Input.id(a.get("questionnaire_version_id")), r);
  }

  @PutMapping("/personality-attempts/{id}/answers")
  ResponseEntity<?> answers(
      @PathVariable String id, @RequestBody Map<String, Object> b, HttpServletRequest r) {
    service.answers(Input.uuid(id), b, r);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/personality-attempts/{id}/result")
  ResponseEntity<?> complete(@PathVariable String id, HttpServletRequest r) {
    return ResponseEntity.status(201).body(service.complete(Input.uuid(id), r));
  }

  @GetMapping("/personality-attempts/{id}/result")
  Object result(@PathVariable String id, HttpServletRequest r) {
    return service.result(Input.uuid(id), r);
  }

  @GetMapping("/personality-attempts/{id}/recommendations")
  Object recommendations(@PathVariable String id, HttpServletRequest r) {
    return service.recommendations(Input.uuid(id), r);
  }

  @PutMapping("/personality-attempts/{id}/saved-result")
  ResponseEntity<?> save(@PathVariable String id, HttpServletRequest r) {
    service.save(Input.uuid(id), r);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/me/personality-results")
  Object history(HttpServletRequest r) {
    return service.history(r);
  }

  @GetMapping(
      value = "/personality-attempts/{id}/report",
      produces = MediaType.APPLICATION_PDF_VALUE)
  ResponseEntity<byte[]> report(@PathVariable String id, HttpServletRequest r) throws IOException {
    UUID uuid = Input.uuid(id), user = access.requireUser(r);
    var attempt = service.own(uuid, r);
    if (!user.toString().equals(attempt.get("owner_user_id")) || attempt.get("saved_at") == null)
      throw ApiException.missing();
    var result = service.result(uuid, r);
    try (var pdf = new PDDocument();
        var out = new ByteArrayOutputStream()) {
      var fontPath = Path.of("/usr/share/fonts/ttf-dejavu/DejaVuSans.ttf");
      if (!java.nio.file.Files.exists(fontPath))
        fontPath = Path.of("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf");
      var font = PDType0Font.load(pdf, fontPath.toFile());
      List<String> lines =
          new ArrayList<>(
              List.of(
                  "UTC - Kết quả khám phá tính cách",
                  "Mã lần làm: " + uuid,
                  "Thời điểm: " + result.get("completed_at")));
      @SuppressWarnings("unchecked")
      var profile = (Map<String, Object>) result.get("profile");
      lines.add(profile.get("name").toString());
      lines.add(profile.get("description").toString());
      lines.add("Điểm: " + result.get("scores"));
      lines.add(result.get("disclaimer").toString());
      List<String> wrapped = new ArrayList<>();
      for (String line : lines)
        for (String paragraph : line.replace('\r', ' ').replace('\t', ' ').split("\n")) {
          while (paragraph.length() > 75) {
            int end = paragraph.lastIndexOf(' ', 75);
            if (end < 1) end = 75;
            wrapped.add(paragraph.substring(0, end));
            paragraph = paragraph.substring(end).stripLeading();
          }
          wrapped.add(paragraph);
        }
      for (int start = 0; start < wrapped.size(); start += 45) {
        var page = new PDPage();
        pdf.addPage(page);
        try (var content = new PDPageContentStream(pdf, page)) {
          content.beginText();
          content.setFont(font, 11);
          content.setLeading(16);
          content.newLineAtOffset(45, 740);
          for (String line : wrapped.subList(start, Math.min(start + 45, wrapped.size()))) {
            content.showText(line);
            content.newLine();
          }
          content.endText();
        }
      }
      pdf.save(out);
      return ResponseEntity.ok()
          .header("Content-Disposition", "attachment; filename=personality-result.pdf")
          .body(out.toByteArray());
    }
  }
}
