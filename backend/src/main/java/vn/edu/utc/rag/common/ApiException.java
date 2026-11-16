package vn.edu.utc.rag.common;

public class ApiException extends RuntimeException {
  public final int status;
  public final String code;

  public ApiException(int status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public static ApiException missing() {
    return new ApiException(404, "NOT_FOUND", "Resource not found.");
  }

  public static ApiException invalid(String message) {
    return new ApiException(422, "VALIDATION_FAILED", message);
  }

  public static ApiException conflict(String message) {
    return new ApiException(409, "CONFLICT", message);
  }
}
