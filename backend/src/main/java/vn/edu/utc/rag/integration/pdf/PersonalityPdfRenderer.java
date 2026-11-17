package vn.edu.utc.rag.integration.pdf;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.stereotype.Component;

@Component
public class PersonalityPdfRenderer {

  public byte[] render(UUID uuid, Map<String, Object> result) throws IOException {
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
      return out.toByteArray();
    }
  }
}
