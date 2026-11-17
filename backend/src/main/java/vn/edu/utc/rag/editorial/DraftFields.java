package vn.edu.utc.rag.editorial;

import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.stereotype.Component;
import vn.edu.utc.rag.common.*;

@Component
public class DraftFields {
  private final JsonCodec json;

  public DraftFields(JsonCodec json) {
    this.json = json;
  }

  public static Object value(String name, Object value) {
    if (value == null) return null;
    if (name.endsWith("_id")) return Input.id(value);
    if (name.endsWith("_config") || name.equals("score_weights")) {
      if (!(value instanceof Map<?, ?>))
        throw ApiException.invalid("Expected configuration object.");
      return value;
    }
    if (Set.of(
            "min_value",
            "max_value",
            "lower_bound",
            "upper_bound",
            "converted_value",
            "score",
            "rank_score")
        .contains(name)) return Input.decimal(value);
    if (Set.of("amount_vnd").contains(name)) {
      if (!(value instanceof String s) || !s.matches("[0-9]{1,15}"))
        throw ApiException.invalid("Expected VND decimal integer string.");
      return Long.parseLong(s);
    }
    if (name.endsWith("_at")) {
      try {
        return OffsetDateTime.parse(value.toString());
      } catch (Exception e) {
        throw ApiException.invalid("Expected RFC 3339 timestamp.");
      }
    }
    if (Set.of("is_required", "enabled").contains(name)) {
      if (!(value instanceof Boolean)) throw ApiException.invalid("Expected boolean.");
      return value;
    }
    if (Set.of(
            "entry_year",
            "admission_year",
            "duration_months",
            "total_credits",
            "credits",
            "suggested_term",
            "position",
            "total_quota",
            "method_quota")
        .contains(name)) {
      if (!(value instanceof Integer n) || n < 0 || n > 1000000)
        throw ApiException.invalid("Expected non-negative integer.");
      return n;
    }
    if (!(value instanceof String s) || s.isBlank() || s.length() > 100000)
      throw ApiException.invalid("Invalid " + name + ".");
    return value;
  }

  public Map<String, Object> fields(Map<String, Object> body, String required, String optional) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (String key : required.split(",")) {
      if (key.isEmpty()) continue;
      if (!body.containsKey(key) || body.get(key) == null)
        throw ApiException.invalid("Missing " + key + ".");
      result.put(key, value(key, body.get(key)));
    }
    for (String key : optional.split(",")) {
      if (!key.isEmpty()) result.put(key, value(key, body.get(key)));
    }
    result.replaceAll(
        (k, v) -> k.endsWith("_config") || k.equals("score_weights") ? json.encode(v) : v);
    return result;
  }
}
