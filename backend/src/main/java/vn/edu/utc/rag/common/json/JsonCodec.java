package vn.edu.utc.rag.common.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class JsonCodec {

  private final ObjectMapper mapper;

  public JsonCodec(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public String encode(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid JSON", e);
    }
  }

  public Map<String, Object> decode(byte[] bytes) throws java.io.IOException {
    return mapper.readValue(
        bytes, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
  }
}
