package vn.edu.utc.rag.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.io.IOException;
import java.math.BigDecimal;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.*;

@Configuration
public class JsonConfiguration {
  @Bean
  Jackson2ObjectMapperBuilderCustomizer wireTypes() {
    return b -> {
      b.serializerByType(Long.class, ToStringSerializer.instance);
      b.serializerByType(Long.TYPE, ToStringSerializer.instance);
      b.serializerByType(
          BigDecimal.class,
          new JsonSerializer<BigDecimal>() {
            @Override
            public void serialize(BigDecimal value, JsonGenerator gen, SerializerProvider provider)
                throws IOException {
              gen.writeString(value.toPlainString());
            }
          });
    };
  }
}
