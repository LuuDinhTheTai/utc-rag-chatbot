package vn.edu.utc.rag.mapper.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class EntityMapper {
  private final ObjectMapper json;

  public EntityMapper(ObjectMapper json) {
    this.json = json;
  }

  public Map<String, Object> of(Object entity) {
    Map<String, Object> row =
        json.convertValue(
            entity, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
    row.remove("new");
    row.replaceAll((k, v) -> v != null && (k.equals("id") || k.endsWith("_id")) ? v.toString() : v);
    return new LinkedHashMap<>(row);
  }

  public Map<String, Object> project(Object entity, String fields) {
    var row = of(entity);
    if (fields.equals("*")) return row;
    Map<String, Object> out = new LinkedHashMap<>();
    for (String f : fields.split(",")) {
      String[] alias = f.trim().split("(?i)\\s+AS\\s+");
      String key = alias[0].replaceFirst("^\\w+\\.", "");
      out.put(alias.length > 1 ? alias[1] : key, row.get(key));
    }
    return out;
  }

  public Object value(Map<String, ?> values, String key, Class<?> type) {
    Object v = values.get(key);
    if (v == null) return null;
    if (type == java.time.Instant.class && v instanceof java.time.OffsetDateTime t)
      return t.toInstant();
    if (type == java.time.Instant.class && v instanceof String s) return java.time.Instant.parse(s);
    if (type == Map.class && v instanceof String s) {
      try {
        return json.readValue(s, Map.class);
      } catch (Exception e) {
        throw new IllegalArgumentException(e);
      }
    }
    return json.convertValue(v, type);
  }
}
