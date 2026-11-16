package vn.edu.utc.rag.common;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {
  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    String id = UUID.randomUUID().toString();
    req.setAttribute("request_id", id);
    res.setHeader("X-Request-ID", id);
    if (req.getRequestURI().startsWith("/api/")) res.setHeader("Cache-Control", "no-store");
    try (var ignored = MDC.putCloseable("request_id", id)) {
      chain.doFilter(req, res);
    }
  }
}
