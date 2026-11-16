package vn.edu.utc.rag.security;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.rate-limits")
public record RateLimitProperties(
    @Min(1) int loginFailures,
    @Min(1) int loginWindowSeconds,
    @Min(1) int chatPerMinute,
    @Min(1) int scoresPerMinute,
    @Min(1) int uploadsPerMinute,
    @Min(1) int jobsPerMinute) {}
