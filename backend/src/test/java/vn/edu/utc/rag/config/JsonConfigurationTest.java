package vn.edu.utc.rag.config;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

class JsonConfigurationTest {
  @Test
  void serializesIdentifiersAndDecimalsAsStrings() throws Exception {
    var builder = new Jackson2ObjectMapperBuilder();
    new JsonConfiguration().wireTypes().customize(builder);
    var mapper = builder.build();
    record Example(long id, BigDecimal score) {}
    var wire =
        mapper.readTree(
            mapper.writeValueAsBytes(new Example(9007199254740993L, new BigDecimal("8.50"))));
    assertThat(wire.get("id").isTextual()).isTrue();
    assertThat(wire.get("id").asText()).isEqualTo("9007199254740993");
    assertThat(wire.get("score").asText()).isEqualTo("8.50");
  }
}
