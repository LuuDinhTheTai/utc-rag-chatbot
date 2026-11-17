package vn.edu.utc.rag.chat;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import vn.edu.utc.rag.ai.OllamaClient;
import vn.edu.utc.rag.common.*;
import vn.edu.utc.rag.security.*;

@Service()
public class ChatService {

  private final Access access;

  private final Pages pages;

  private final ChatStore store;

  private final OllamaClient ai;

  private final RateLimits limits;

  public ChatService(
      Access access,
      Pages pages,
      ChatStore store,
      OllamaClient ai,
      RateLimits limits,
      ChatReadDataRepository repository) {
    this.access = access;
    this.pages = pages;
    this.store = store;
    this.ai = ai;
    this.limits = limits;
    this.repository = repository;
  }

  public ResponseEntity<?> create(Map<String, Object> b, HttpServletRequest req) {
    Input.only(b, "display_title");
    UUID user = access.user(req);
    var guest = access.guest(req, false);
    if (user == null && guest == null)
      throw new ApiException(401, "UNAUTHENTICATED", "Initialize a guest session first.");
    var args = new HashMap<String, Object>();
    args.put("u", user);
    args.put("g", user == null ? guest.hash() : null);
    args.put(
        "expiry", user == null ? OffsetDateTime.ofInstant(guest.expires(), ZoneOffset.UTC) : null);
    args.put("title", b.containsKey("display_title") ? Input.text(b, "display_title") : null);
    var row = repository.findConversationsForCreate(args);
    return ResponseEntity.created(URI.create("/api/v1/conversations/" + row.get("id"))).body(row);
  }

  public Object history(HttpServletRequest req) {
    UUID u = access.requireUser(req);
    return repository.findPageForHistory(Map.of("u", u), pages.request(req));
  }

  public Object conversation(String id, HttpServletRequest req) {
    var r = access.own("conversations", Input.uuid(id), req);
    r.remove("owner_user_id");
    r.remove("guest_token_hash");
    return r;
  }

  @Transactional
  public ResponseEntity<?> delete(String id, HttpServletRequest req) {
    UUID c = Input.uuid(id);
    repository.findConversationsForDelete(Map.of("id", c));
    access.own("conversations", c, req);
    repository.applyMessageCitationsForDelete(Map.of("id", c));
    repository.applyMessagesForDelete(Map.of("id", c));
    repository.applyConversationsForDelete(Map.of("id", c));
    return ResponseEntity.noContent().build();
  }

  public Object messages(String id, HttpServletRequest req) {
    UUID c = Input.uuid(id);
    access.own("conversations", c, req);
    var result = repository.findPageForMessages(Map.of("id", c), pages.request(req));
    @SuppressWarnings("unchecked")
    var data = (List<Map<String, Object>>) result.get("data");
    return Map.of(
        "data",
        data.stream().map(m -> store.message(c, UUID.fromString(m.get("id").toString()))).toList(),
        "pagination",
        result.get("pagination"));
  }

  public Object message(String id, String message, HttpServletRequest req) {
    UUID c = Input.uuid(id);
    access.own("conversations", c, req);
    return store.message(c, Input.uuid(message));
  }

  public ResponseEntity<?> send(String id, Map<String, Object> b, HttpServletRequest req) {
    UUID c = Input.uuid(id);
    access.own("conversations", c, req);
    Input.only(b, "client_message_id", "content");
    UUID client = Input.uuid(Input.text(b, "client_message_id"));
    String question = Input.text(b, "content");
    if (question.length() > 4000) throw ApiException.invalid("Question is too long.");
    var replay = store.replay(c, client, question);
    if (replay != null) return ResponseEntity.ok(replay);
    limits.hit("chat:" + access.caller(req), 10, Duration.ofMinutes(1));
    var available = repository.findDocumentRevisionsForSend(Map.of());
    List<Map<String, Object>> chunks = List.of();
    if (Long.parseLong(available.get("n").toString()) > 0) {
      String embedding = ai.embed(question);
      chunks = repository.findDocumentChunksForSend(Map.of("v", embedding));
    }
    String context =
        chunks.stream()
            .map(m -> "Nguồn: " + m.get("title") + "\n" + m.get("body"))
            .collect(java.util.stream.Collectors.joining("\n\n"));
    String answer =
        chunks.isEmpty()
            ? "Hiện chưa có đủ nguồn UTC đã xác minh để trả lời câu hỏi này. Bạn vui lòng xem thông"
                + " báo chính thức hoặc liên hệ bộ phận tuyển sinh."
            : ai.answer(question, context);
    var result = store.save(c, client, question, answer, chunks, req);
    return ResponseEntity.status(Boolean.TRUE.equals(result.get("replayed")) ? 200 : 201)
        .body(result.get("turn"));
  }

  @Transactional
  public ResponseEntity<?> feedback(
      String id, String message, Map<String, Object> b, HttpServletRequest req) {
    UUID c = Input.uuid(id), m = Input.uuid(message);
    access.own("conversations", c, req);
    Input.only(b, "is_helpful", "comment_text");
    if (!(b.get("is_helpful") instanceof Boolean))
      throw ApiException.invalid("is_helpful is required.");
    repository.findMessagesForFeedback(Map.of("id", m, "c", c));
    var args = new HashMap<String, Object>();
    args.put("id", m);
    args.put("helpful", b.get("is_helpful"));
    args.put("comment", b.get("comment_text") == null ? null : Input.text(b, "comment_text"));
    repository.applyChatFeedbackEntriesForFeedback(args);
    return ResponseEntity.noContent().build();
  }

  private final ChatReadDataRepository repository;
}
