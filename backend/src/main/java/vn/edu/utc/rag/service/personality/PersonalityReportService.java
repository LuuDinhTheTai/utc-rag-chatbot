package vn.edu.utc.rag.service.personality;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.utc.rag.exception.ApiException;
import vn.edu.utc.rag.integration.pdf.PersonalityPdfRenderer;
import vn.edu.utc.rag.security.Access;

@Service
public class PersonalityReportService {
  private final PersonalityService attempts;
  private final Access access;
  private final PersonalityPdfRenderer renderer;

  public PersonalityReportService(
      PersonalityService attempts, Access access, PersonalityPdfRenderer renderer) {
    this.attempts = attempts;
    this.access = access;
    this.renderer = renderer;
  }

  @Transactional(readOnly = true)
  public byte[] export(UUID id, HttpServletRequest req) throws IOException {
    var user = access.requireUser(req);
    var attempt = attempts.own(id, req);
    if (!user.toString().equals(attempt.get("owner_user_id")) || attempt.get("saved_at") == null)
      throw ApiException.missing();
    return renderer.render(id, attempts.result(id, req));
  }
}
