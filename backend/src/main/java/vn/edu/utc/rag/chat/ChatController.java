package vn.edu.utc.rag.chat;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import vn.edu.utc.rag.ai.OllamaClient;
import vn.edu.utc.rag.common.*;
import vn.edu.utc.rag.security.*;

@RestController
@RequestMapping("/api/v1")
public class ChatController {
  private final Db db;
  private final Access access;
  private final Pages pages;
  private final ChatStore store;
  private final OllamaClient ai;
  private final RateLimits limits;

  public ChatController(
      Db db, Access access, Pages pages, ChatStore store, OllamaClient ai, RateLimits limits) {
    this.db = db;
    this.access = access;
    this.pages = pages;
    this.store = store;
    this.ai = ai;
    this.limits = limits;
  }

  @PostMapping("/conversations")
  ResponseEntity<?> create(@RequestBody Map<String, Object> b, HttpServletRequest req) {
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
    var row =
        db.one(
            "INSERT INTO"
                + " conversations(owner_user_id,guest_token_hash,display_title,expires_at,created_at,updated_at)"
                + " VALUES(:u,:g,:title,:expiry,now(),now()) RETURNING"
                + " id,display_title,expires_at,created_at,updated_at",
            args);
    return ResponseEntity.created(URI.create("/api/v1/conversations/" + row.get("id"))).body(row);
  }

  @GetMapping("/me/conversations")
  Object history(HttpServletRequest req) {
    UUID u = access.requireUser(req);
    var args = new HashMap<String, Object>(Map.of("u", u));
    String filter = "";
    if (req.getParameter("q") != null) {
      filter =
          " AND (display_title ILIKE :q OR EXISTS(SELECT 1 FROM messages m WHERE"
              + " m.conversation_id=c.id AND m.body ILIKE :q))";
      args.put("q", "%" + req.getParameter("q") + "%");
    }
    return pages.page(
        "SELECT id,display_title,created_at,updated_at FROM conversations c WHERE owner_user_id=:u"
            + filter,
        args,
        req,
        "updated_at:timestamptz:desc",
        "id:uuid:desc");
  }

  @GetMapping("/conversations/{id}")
  Object conversation(@PathVariable String id, HttpServletRequest req) {
    var r = access.own("conversations", Input.uuid(id), req);
    r.remove("owner_user_id");
    r.remove("guest_token_hash");
    return r;
  }

  @DeleteMapping("/conversations/{id}")
  @Transactional
  ResponseEntity<?> delete(@PathVariable String id, HttpServletRequest req) {
    UUID c = Input.uuid(id);
    db.one("SELECT id FROM conversations WHERE id=:id FOR UPDATE", Map.of("id", c));
    access.own("conversations", c, req);
    db.update(
        "DELETE FROM message_citations WHERE message_id IN (SELECT id FROM messages WHERE"
            + " conversation_id=:id)",
        Map.of("id", c));
    db.update("DELETE FROM messages WHERE conversation_id=:id", Map.of("id", c));
    db.update("DELETE FROM conversations WHERE id=:id", Map.of("id", c));
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/conversations/{id}/messages")
  Object messages(@PathVariable String id, HttpServletRequest req) {
    UUID c = Input.uuid(id);
    access.own("conversations", c, req);
    var result =
        pages.page(
            "SELECT id,position FROM messages WHERE conversation_id=:id",
            Map.of("id", c),
            req,
            "position:integer:asc",
            "id:uuid:asc");
    @SuppressWarnings("unchecked")
    var data = (List<Map<String, Object>>) result.get("data");
    return Map.of(
        "data",
        data.stream().map(m -> store.message(c, UUID.fromString(m.get("id").toString()))).toList(),
        "pagination",
        result.get("pagination"));
  }

  @GetMapping("/conversations/{id}/messages/{message}")
  Object message(@PathVariable String id, @PathVariable String message, HttpServletRequest req) {
    UUID c = Input.uuid(id);
    access.own("conversations", c, req);
    return store.message(c, Input.uuid(message));
  }

  @PostMapping("/conversations/{id}/messages")
  ResponseEntity<?> send(
      @PathVariable String id, @RequestBody Map<String, Object> b, HttpServletRequest req) {
    UUID c = Input.uuid(id);
    access.own("conversations", c, req);
    Input.only(b, "client_message_id", "content");
    UUID client = Input.uuid(Input.text(b, "client_message_id"));
    String question = Input.text(b, "content");
    if (question.length() > 4000) throw ApiException.invalid("Question is too long.");
    var replay = store.replay(c, client, question);
    if (replay != null) return ResponseEntity.ok(replay);
    limits.hit("chat:" + access.caller(req), 10, Duration.ofMinutes(1));
    var available =
        db.one(
            "SELECT count(*) AS n FROM document_revisions WHERE status='PUBLISHED' AND"
                + " source_verified AND revoked_at IS NULL",
            Map.of());
    List<Map<String, Object>> chunks = List.of();
    if (Long.parseLong(available.get("n").toString()) > 0) {
      String embedding = ai.embed(question);
      chunks =
          db.list(
              "SELECT ch.id,ch.revision_id,left(ch.body,3000) AS"
                  + " body,ch.locator,r.title,r.official_url,r.admission_year FROM document_chunks"
                  + " ch JOIN document_revisions r ON r.id=ch.revision_id WHERE"
                  + " r.status='PUBLISHED' AND r.source_verified AND r.revoked_at IS NULL AND"
                  + " (ch.embedding <=> CAST(:v AS vector))<0.6 ORDER BY ch.embedding <=> CAST(:v"
                  + " AS vector),ch.id LIMIT 3",
              Map.of("v", embedding));
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

  @PutMapping("/conversations/{id}/messages/{message}/feedback")
  @Transactional
  ResponseEntity<?> feedback(
      @PathVariable String id,
      @PathVariable String message,
      @RequestBody Map<String, Object> b,
      HttpServletRequest req) {
    UUID c = Input.uuid(id), m = Input.uuid(message);
    access.own("conversations", c, req);
    Input.only(b, "is_helpful", "comment_text");
    if (!(b.get("is_helpful") instanceof Boolean))
      throw ApiException.invalid("is_helpful is required.");
    db.one(
        "SELECT id FROM messages WHERE id=:id AND conversation_id=:c AND author_type='ASSISTANT'",
        Map.of("id", m, "c", c));
    var args = new HashMap<String, Object>();
    args.put("id", m);
    args.put("helpful", b.get("is_helpful"));
    args.put("comment", b.get("comment_text") == null ? null : Input.text(b, "comment_text"));
    db.update(
        "INSERT INTO chat_feedback_entries(answer_id,message_id,is_helpful,comment_text,created_at)"
            + " VALUES(:id,:id,:helpful,:comment,now()) ON CONFLICT(answer_id) DO UPDATE SET"
            + " is_helpful=excluded.is_helpful,comment_text=excluded.comment_text",
        args);
    return ResponseEntity.noContent().build();
  }
}
