package vn.edu.utc.rag.integration.pdf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.Loader;
import org.springframework.stereotype.Component;
import vn.edu.utc.rag.exception.ApiException;

@Component
public class PdfInspector {
  public void validate(byte[] bytes) throws IOException {
    if (bytes.length < 5 || !new String(bytes, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-"))
      throw new ApiException(415, "UNSUPPORTED_MEDIA", "Expected PDF.");
    try (var pdf = Loader.loadPDF(bytes)) {
      if (pdf.isEncrypted() || pdf.getNumberOfPages() < 1)
        throw new ApiException(
            415, "UNSUPPORTED_MEDIA", "Encrypted or empty PDF is not supported.");
    }
  }
}
