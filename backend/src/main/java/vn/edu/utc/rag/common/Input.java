package vn.edu.utc.rag.common;

import java.math.BigDecimal;
import java.util.*;

public final class Input {
  private Input() {}

  public static String text(Map<String, Object> body, String key) {
    Object value = body.get(key);
    if (!(value instanceof String s) || s.isBlank() || s.length() > 20000)
      throw ApiException.invalid("Invalid " + key + ".");
    return s;
  }

  public static long id(Object value) {
    try {
      if (!(value instanceof String s) || !s.matches("[1-9][0-9]{0,18}"))
        throw new IllegalArgumentException();
      return Long.parseLong(s);
    } catch (RuntimeException e) {
      throw new ApiException(400, "BAD_REQUEST", "Expected a positive decimal ID string.");
    }
  }

  public static UUID uuid(String s) {
    try {
      var id = UUID.fromString(s);
      if (!id.toString().equalsIgnoreCase(s)) throw new IllegalArgumentException();
      return id;
    } catch (RuntimeException e) {
      throw new ApiException(400, "BAD_REQUEST", "Invalid UUID.");
    }
  }

  public static BigDecimal decimal(Object v) {
    try {
      if (!(v instanceof String s) || !s.matches("-?[0-9]{1,12}(\\.[0-9]{1,8})?"))
        throw new IllegalArgumentException();
      return new BigDecimal(s);
    } catch (RuntimeException e) {
      throw ApiException.invalid("Expected a decimal string.");
    }
  }

  public static void only(Map<String, Object> body, String... fields) {
    var allowed = Set.of(fields);
    if (!allowed.containsAll(body.keySet())) throw ApiException.invalid("Unknown request field.");
  }

  @SuppressWarnings("unchecked")
  public static List<Map<String, Object>> objects(Map<String, Object> body, String key) {
    Object v = body.get(key);
    if (!(v instanceof List<?> list)
        || list.size() > 1000
        || list.stream().anyMatch(x -> !(x instanceof Map)))
      throw ApiException.invalid("Invalid " + key + ".");
    return (List<Map<String, Object>>) v;
  }
}
