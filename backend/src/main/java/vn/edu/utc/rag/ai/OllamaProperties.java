package vn.edu.utc.rag.ai;

import jakarta.validation.constraints.*;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.ollama")
public record OllamaProperties(
    @NotNull URI baseUrl,
    @NotBlank String slmModel,
    @NotBlank String embeddingModel,
    @Min(768) @Max(768) int embeddingDimension,
    @NotNull Duration connectTimeout,
    @NotNull Duration readTimeout) {
  @AssertTrue
  public boolean isValidConnection() {
    return baseUrl != null
        && baseUrl.getHost() != null
        && java.util.Set.of("http", "https").contains(baseUrl.getScheme())
        && connectTimeout != null
        && connectTimeout.isPositive()
        && readTimeout != null
        && readTimeout.isPositive();
  }
}
