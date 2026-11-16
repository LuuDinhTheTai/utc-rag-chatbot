package vn.edu.utc.rag.chat;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.utc.rag.common.*;
import vn.edu.utc.rag.security.Access;

@Service
public class ChatStore {
  private final Db db;
  private final Access access;

  public ChatStore(Db db, Access access) {
    this.db = db;
    this.access = access;
  }

  public Map<String, Object> message(UUID conversation, UUID id) {
    var row =
        db.one(
            "SELECT id,author_type AS author,body AS content,created_at,answer_status FROM messages"
                + " WHERE id=:id AND conversation_id=:c",
            Map.of("id", id, "c", conversation));
    row.put(
        "citations",
        db.list(
            "SELECT source_title AS title,source_url AS url,source_year AS year,locator FROM"
                + " message_citations WHERE message_id=:id ORDER BY position",
            Map.of("id", id)));
    return row;
  }

  public Map<String, Object> replay(UUID conversation, UUID clientId, String content) {
    var rows =
        db.list(
            "SELECT id,conversation_id,body,author_type FROM messages WHERE id=:id",
            Map.of("id", clientId));
    if (rows.isEmpty()) return null;
    var old = rows.getFirst();
    if (!conversation.toString().equals(old.get("conversation_id"))
        || !content.equals(old.get("body"))
        || !"USER".equals(old.get("author_type")))
      throw new ApiException(
          409, "CLIENT_MESSAGE_ID_CONFLICT", "Message ID was already used with different content.");
    var answer = db.one("SELECT id FROM messages WHERE reply_to_id=:id", Map.of("id", clientId));
    return Map.of(
        "user_message",
        message(conversation, clientId),
        "assistant_message",
        message(conversation, UUID.fromString(answer.get("id").toString())));
  }

  @Transactional
  public Map<String, Object> save(
      UUID conversation,
      UUID clientId,
      String question,
      String answer,
      List<Map<String, Object>> chunks,
      HttpServletRequest req) {
    db.one("SELECT id FROM conversations WHERE id=:id FOR UPDATE", Map.of("id", conversation));
    access.own("conversations", conversation, req);
    var replay = replay(conversation, clientId, question);
    if (replay != null) return Map.of("replayed", true, "turn", replay);
    for (var chunk : chunks)
      db.one(
          "SELECT r.id FROM document_revisions r WHERE r.id=:id AND r.status='PUBLISHED' AND"
              + " r.source_verified AND r.revoked_at IS NULL FOR SHARE",
          Map.of("id", Input.id(chunk.get("revision_id"))));
    var pos =
        db.one(
            "SELECT coalesce(max(position),0)+1 AS n FROM messages WHERE conversation_id=:c",
            Map.of("c", conversation));
    int position = ((Number) pos.get("n")).intValue();
    UUID assistant = UUID.randomUUID();
    db.update(
        "INSERT INTO messages(id,conversation_id,position,author_type,body,created_at)"
            + " VALUES(:id,:c,:p,'USER',:body,now())",
        Map.of("id", clientId, "c", conversation, "p", position, "body", question));
    db.update(
        "INSERT INTO"
            + " messages(id,conversation_id,position,author_type,body,created_at,answer_status,reply_to_id)"
            + " VALUES(:id,:c,:p,'ASSISTANT',:body,now(),:status,:reply)",
        Map.of(
            "id",
            assistant,
            "c",
            conversation,
            "p",
            position + 1,
            "body",
            answer,
            "status",
            chunks.isEmpty() ? "INSUFFICIENT_EVIDENCE" : "ANSWERED",
            "reply",
            clientId));
    int n = 0;
    for (var chunk : chunks) {
      var args = new HashMap<String, Object>();
      args.put("m", assistant);
      args.put("p", ++n);
      args.put("id", Input.id(chunk.get("id")));
      args.put("title", chunk.get("title"));
      args.put("url", chunk.get("official_url"));
      args.put("year", chunk.get("admission_year"));
      args.put("locator", chunk.get("locator"));
      db.update(
          "INSERT INTO"
              + " message_citations(message_id,position,chunk_id,source_title,source_url,source_year,locator)"
              + " VALUES(:m,:p,:id,:title,:url,:year,:locator)",
          args);
    }
    db.update(
        "UPDATE conversations SET updated_at=now(),display_title=coalesce(display_title,:title)"
            + " WHERE id=:id",
        Map.of(
            "id", conversation, "title", question.substring(0, Math.min(100, question.length()))));
    return Map.of(
        "replayed",
        false,
        "turn",
        Map.of(
            "user_message",
            message(conversation, clientId),
            "assistant_message",
            message(conversation, assistant)));
  }
}
