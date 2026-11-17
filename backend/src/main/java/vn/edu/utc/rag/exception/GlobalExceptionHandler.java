package vn.edu.utc.rag.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import org.slf4j.LoggerFactory;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@Component
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  private final ObjectMapper mapper;

  public GlobalExceptionHandler(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public ProblemDetail body(int status, String code, String message, HttpServletRequest req) {
    var p = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), message);
    String slug =
        switch (status) {
          case 400 -> "bad-request";
          case 401 -> "unauthenticated";
          case 403 -> "forbidden";
          case 404 -> "not-found";
          case 405 -> "method-not-allowed";
          case 409 -> "conflict";
          case 412 -> "precondition-failed";
          case 413 -> "payload-too-large";
          case 415 -> "unsupported-media-type";
          case 422 -> "validation-error";
          case 428 -> "precondition-required";
          case 429 -> "rate-limited";
          case 503 -> "service-unavailable";
          case 504 -> "upstream-timeout";
          default -> "internal-error";
        };
    p.setType(URI.create("/api/problems/" + slug));
    p.setProperty("code", code);
    p.setProperty("request_id", req.getAttribute("request_id"));
    p.setProperty("errors", List.of());
    return p;
  }

  public void write(
      HttpServletRequest req, HttpServletResponse res, int status, String code, String message)
      throws IOException {
    res.setStatus(status);
    res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    res.setHeader("Cache-Control", "no-store");
    mapper.writeValue(res.getOutputStream(), body(status, code, message, req));
  }

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ProblemDetail> api(ApiException e, HttpServletRequest req) {
    var headers = new HttpHeaders();
    if (e.status == 429) headers.set("Retry-After", "60");
    return new ResponseEntity<>(
        body(e.status, e.code, e.getMessage(), req), headers, HttpStatusCode.valueOf(e.status));
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<ProblemDetail> integrity(HttpServletRequest req) {
    return ResponseEntity.status(409)
        .body(body(409, "CONFLICT", "A constraint prevents this operation.", req));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ProblemDetail> unexpected(Exception e, HttpServletRequest req) {
    boolean unavailable =
        e instanceof TransientDataAccessException
            || e instanceof DataAccessResourceFailureException;
    int status = unavailable ? 503 : 500;
    // Deliberately omit request bodies, SQL, credentials and exception messages from logs.
    LoggerFactory.getLogger(GlobalExceptionHandler.class)
        .error(
            "request_id={} exception={}",
            req.getAttribute("request_id"),
            e.getClass().getSimpleName());
    return ResponseEntity.status(status)
        .body(
            body(
                status,
                unavailable ? "SERVICE_UNAVAILABLE" : "INTERNAL_ERROR",
                "Unable to complete the request.",
                req));
  }

  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception e, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    String code =
        switch (status.value()) {
          case 400 -> "BAD_REQUEST";
          case 404 -> "NOT_FOUND";
          case 405 -> "METHOD_NOT_ALLOWED";
          case 413 -> "TOO_LARGE";
          case 415 -> "UNSUPPORTED_MEDIA";
          default -> "INTERNAL_ERROR";
        };
    var req = ((ServletWebRequest) request).getRequest();
    return new ResponseEntity<>(
        body(status.value(), code, "The request could not be processed.", req), headers, status);
  }

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException e,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    var p =
        body(
            422,
            "VALIDATION_FAILED",
            "Invalid request fields.",
            ((ServletWebRequest) request).getRequest());
    p.setProperty(
        "errors",
        e.getBindingResult().getFieldErrors().stream()
            .map(x -> Map.of("field", x.getField(), "code", "INVALID", "message", "Invalid value."))
            .toList());
    return new ResponseEntity<>(p, headers, HttpStatus.UNPROCESSABLE_ENTITY);
  }
}
