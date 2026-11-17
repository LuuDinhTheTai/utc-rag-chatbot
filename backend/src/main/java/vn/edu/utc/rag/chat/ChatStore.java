package vn.edu.utc.rag.chat;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.utc.rag.common.*;
import vn.edu.utc.rag.security.Access;

@Service
public class ChatStore {

  private final Access access;

  public ChatStore(Access access, ChatDataRepository repository) {
    this.access = access;
    this.repository = repository;
  }

  public Map<String, Object> message(UUID conversation, UUID id) {
    var row = repository.findMessagesForMessage(Map.of("id", id, "c", conversation));
    row.put("citations", repository.findMessageCitationsForMessage(Map.of("id", id)));
    return row;
  }

  public Map<String, Object> replay(UUID conversation, UUID clientId, String content) {
    var rows = repository.findMessagesForReplay(Map.of("id", clientId));
    if (rows.isEmpty()) return null;
    var old = rows.getFirst();
    if (!conversation.toString().equals(old.get("conversation_id"))
        || !content.equals(old.get("body"))
        || !"USER".equals(old.get("author_type")))
      throw new ApiException(
          409, "CLIENT_MESSAGE_ID_CONFLICT", "Message ID was already used with different content.");
    var answer = repository.findMessagesForReplay2(Map.of("id", clientId));
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
    repository.findConversationsForSave(Map.of("id", conversation));
    access.own("conversations", conversation, req);
    var replay = replay(conversation, clientId, question);
    if (replay != null) return Map.of("replayed", true, "turn", replay);
    for (var chunk : chunks)
      repository.findDocumentRevisionsForSave(Map.of("id", Input.id(chunk.get("revision_id"))));
    var pos = repository.findMessagesForSave(Map.of("c", conversation));
    int position = ((Number) pos.get("n")).intValue();
    UUID assistant = UUID.randomUUID();
    repository.applyMessagesForSave(
        Map.of("id", clientId, "c", conversation, "p", position, "body", question));
    repository.applyMessagesForSave2(
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
      repository.applyMessageCitationsForSave(args);
    }
    repository.applyConversationsForSave(
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

  private final ChatDataRepository repository;
}
