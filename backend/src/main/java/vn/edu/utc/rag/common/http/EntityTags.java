package vn.edu.utc.rag.common.http;

import vn.edu.utc.rag.exception.ApiException;

public final class EntityTags {

  private EntityTags() {}

  public static String etag(Object value, String prefix) {
    return "\"" + prefix + "-" + value + "\"";
  }

  public static void match(String provided, Object value, String prefix) {
    if (provided == null)
      throw new ApiException(428, "PRECONDITION_REQUIRED", "If-Match is required.");
    if (!etag(value, prefix).equals(provided))
      throw new ApiException(412, "PRECONDITION_FAILED", "Resource has changed.");
  }
}
