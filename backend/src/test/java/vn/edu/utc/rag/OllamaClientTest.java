package vn.edu.utc.rag;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.*;
import vn.edu.utc.rag.ai.*;
import vn.edu.utc.rag.common.ApiException;

class OllamaClientTest {
  HttpServer server;

  OllamaClient client(String response, int status, Duration timeout) throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          try {
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
          } finally {
            exchange.close();
          }
        });
    server.start();
    return new OllamaClient(
        new OllamaProperties(
            URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
            "qwen3:0.6b",
            "embeddinggemma:300m-qat-q4_0",
            768,
            Duration.ofSeconds(1),
            timeout));
  }

  @AfterEach
  void stop() {
    if (server != null) server.stop(0);
  }

  @Test
  void accepts768Dimensions() throws Exception {
    var ai = client("{\"embeddings\":[[1," + "0,".repeat(766) + "0]]}", 200, Duration.ofSeconds(1));
    assertThat(ai.embed("Question").split(",")).hasSize(768);
  }

  @Test
  void rejectsDimensionMismatch() throws Exception {
    var ai = client("{\"embeddings\":[[1,2]]}", 200, Duration.ofSeconds(1));
    assertThatThrownBy(() -> ai.embed("Question"))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status).isEqualTo(503));
  }

  @Test
  void upstreamErrorsDoNotLeakResponse() throws Exception {
    var ai = client("{\"error\":\"private upstream trace\"}", 500, Duration.ofSeconds(1));
    assertThatThrownBy(() -> ai.answer("Q", "context"))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.status).isEqualTo(503);
              assertThat(e.getMessage()).doesNotContain("private");
            });
  }

  @Test
  void answerParsesTheOllamaResponse() throws Exception {
    var ai = client("{\"message\":{\"content\":\"Câu trả lời\"}}", 200, Duration.ofSeconds(1));
    assertThat(ai.answer("Q", "context")).isEqualTo("Câu trả lời");
  }
}
