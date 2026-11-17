package vn.edu.utc.rag.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import vn.edu.utc.rag.persistence.query.QueryPage;
import vn.edu.utc.rag.security.Access;

@Component
public class Pages {
  private final Access access;

  public Pages(Access access) {
    this.access = access;
  }

  public QueryPage request(HttpServletRequest req) {
    return new QueryPage(req.getRequestURI(), access.caller(req), req.getParameterMap());
  }
}
