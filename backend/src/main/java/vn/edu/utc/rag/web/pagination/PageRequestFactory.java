package vn.edu.utc.rag.web.pagination;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import vn.edu.utc.rag.persistence.query.QueryPage;
import vn.edu.utc.rag.security.Access;

@Component
public class PageRequestFactory {
  private final Access access;

  public PageRequestFactory(Access access) {
    this.access = access;
  }

  public QueryPage request(HttpServletRequest req) {
    return new QueryPage(req.getRequestURI(), access.caller(req), req.getParameterMap());
  }
}
