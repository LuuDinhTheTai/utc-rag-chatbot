package vn.edu.utc.rag.security;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;
import vn.edu.utc.rag.common.ApiException;

@Component
public class RateLimits {

  private final Map<String, ArrayDeque<Instant>> windows = new HashMap<>();

  private final RateLimitProperties properties;

  public RateLimits(RateLimitProperties properties) {
    this.properties = properties;
  }

  public synchronized void check(String key, int maximum, Duration window, boolean record) {
    String group = key.substring(0, key.indexOf(':'));
    maximum =
        switch (group) {
          case "login" -> properties.loginFailures();
          case "chat" -> properties.chatPerMinute();
          case "score" -> properties.scoresPerMinute();
          case "upload" -> properties.uploadsPerMinute();
          case "job" -> properties.jobsPerMinute();
          default -> maximum;
        };
    if (group.equals("login")) window = Duration.ofSeconds(properties.loginWindowSeconds());
    var now = Instant.now();
    windows
        .entrySet()
        .removeIf(
            e ->
                e.getValue().isEmpty()
                    || e.getValue().getLast().isBefore(now.minus(Duration.ofHours(1))));
    if (!windows.containsKey(key) && windows.size() >= 50000)
      throw new ApiException(429, "RATE_LIMITED", "Rate limit storage is full. Try again later.");
    var times = windows.computeIfAbsent(key, k -> new ArrayDeque<>());
    while (!times.isEmpty() && times.getFirst().isBefore(now.minus(window))) times.removeFirst();
    if (times.size() >= maximum || windows.size() > 50000)
      throw new ApiException(429, "RATE_LIMITED", "Rate limit exceeded. Try again later.");
    if (record) times.addLast(now);
  }

  public void hit(String key, int maximum, Duration window) {
    check(key, maximum, window, true);
  }
}
