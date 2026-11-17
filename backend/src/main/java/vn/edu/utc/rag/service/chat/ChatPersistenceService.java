package vn.edu.utc.rag.service.chat;

import jakarta.servlet.http.HttpServletRequest;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.utc.rag.exception.ApiException;
import vn.edu.utc.rag.security.Access;
import vn.edu.utc.rag.validation.Input;

@Service
@Transactional()
public class ChatPersistenceService {

  private final Access access;

  public ChatPersistenceService(
      Access access,
      vn.edu.utc.rag.mapper.common.EntityMapper views,
      vn.edu.utc.rag.persistence.repository.MessagesRepository messagesJpa,
      vn.edu.utc.rag.persistence.repository.MessageCitationsRepository messageCitationsJpa,
      vn.edu.utc.rag.persistence.repository.ConversationsRepository conversationsJpa,
      vn.edu.utc.rag.persistence.repository.DocumentRevisionsRepository documentRevisionsJpa) {
    this.access = access;
    this.views = views;
    this.messagesJpa = messagesJpa;
    this.messageCitationsJpa = messageCitationsJpa;
    this.conversationsJpa = conversationsJpa;
    this.documentRevisionsJpa = documentRevisionsJpa;
  }

  public Map<String, Object> message(UUID conversation, UUID id) {
    var row = findMessagesForMessage(Map.of("id", id, "c", conversation));
    row.put("citations", findMessageCitationsForMessage(Map.of("id", id)));
    return row;
  }

  public Map<String, Object> replay(UUID conversation, UUID clientId, String content) {
    var rows = findMessagesForReplay(Map.of("id", clientId));
    if (rows.isEmpty()) return null;
    var old = rows.getFirst();
    if (!conversation.toString().equals(old.get("conversation_id"))
        || !content.equals(old.get("body"))
        || !"USER".equals(old.get("author_type")))
      throw new ApiException(
          409, "CLIENT_MESSAGE_ID_CONFLICT", "Message ID was already used with different content.");
    var answer = findMessagesForReplay2(Map.of("id", clientId));
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
    findConversationsForSave(Map.of("id", conversation));
    access.own("conversations", conversation, req);
    var replay = replay(conversation, clientId, question);
    if (replay != null) return Map.of("replayed", true, "turn", replay);
    for (var chunk : chunks)
      findDocumentRevisionsForSave(Map.of("id", Input.id(chunk.get("revision_id"))));
    var pos = findMessagesForSave(Map.of("c", conversation));
    int position = ((Number) pos.get("n")).intValue();
    UUID assistant = UUID.randomUUID();
    applyMessagesForSave(
        Map.of("id", clientId, "c", conversation, "p", position, "body", question));
    applyMessagesForSave2(
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
      applyMessageCitationsForSave(args);
    }
    applyConversationsForSave(
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

  private Map<String, Object> findMessagesForMessage(Map<String, ?> parameters) {
    var result =
        messagesJpa.findAllByIdAndConversationId(
            (java.util.UUID) views.value(parameters, "id", java.util.UUID.class),
            (java.util.UUID) views.value(parameters, "c", java.util.UUID.class));
    var rows =
        result.stream()
            .map(
                e ->
                    views.project(
                        e, "id,author_type AS author,body AS content,created_at,answer_status"))
            .toList();
    if (rows.isEmpty()) throw vn.edu.utc.rag.exception.ApiException.missing();
    return rows.getFirst();
  }

  private final vn.edu.utc.rag.mapper.common.EntityMapper views;

  private final vn.edu.utc.rag.persistence.repository.MessagesRepository messagesJpa;

  private List<Map<String, Object>> findMessageCitationsForMessage(Map<String, ?> parameters) {
    var result =
        messageCitationsJpa.findAllByMessageIdOrderByPositionAsc(
            (java.util.UUID) views.value(parameters, "id", java.util.UUID.class));
    var rows =
        result.stream()
            .map(
                e ->
                    views.project(
                        e, "source_title AS title,source_url AS url,source_year AS year,locator"))
            .toList();
    return rows;
  }

  private final vn.edu.utc.rag.persistence.repository.MessageCitationsRepository
      messageCitationsJpa;

  private List<Map<String, Object>> findMessagesForReplay(Map<String, ?> parameters) {
    var result =
        messagesJpa.findById((java.util.UUID) views.value(parameters, "id", java.util.UUID.class));
    var rows =
        result.stream().map(e -> views.project(e, "id,conversation_id,body,author_type")).toList();
    return rows;
  }

  private Map<String, Object> findMessagesForReplay2(Map<String, ?> parameters) {
    var result =
        messagesJpa.findAllByReplyToId(
            (java.util.UUID) views.value(parameters, "id", java.util.UUID.class));
    var rows = result.stream().map(e -> views.project(e, "id")).toList();
    if (rows.isEmpty()) throw vn.edu.utc.rag.exception.ApiException.missing();
    return rows.getFirst();
  }

  private Map<String, Object> findConversationsForSave(Map<String, ?> parameters) {
    var result =
        conversationsJpa.findLockedById(
            (java.util.UUID) views.value(parameters, "id", java.util.UUID.class));
    var rows = result.stream().map(e -> views.project(e, "id")).toList();
    if (rows.isEmpty()) throw vn.edu.utc.rag.exception.ApiException.missing();
    return rows.getFirst();
  }

  private final vn.edu.utc.rag.persistence.repository.ConversationsRepository conversationsJpa;

  private Map<String, Object> findDocumentRevisionsForSave(Map<String, ?> parameters) {
    var result =
        documentRevisionsJpa.findPublishedVerifiedSourceForRead(
            (Long) views.value(parameters, "id", Long.class));
    var rows = result.stream().map(e -> views.project(e, "r.id")).toList();
    if (rows.isEmpty()) throw vn.edu.utc.rag.exception.ApiException.missing();
    return rows.getFirst();
  }

  private final vn.edu.utc.rag.persistence.repository.DocumentRevisionsRepository
      documentRevisionsJpa;

  private Map<String, Object> findMessagesForSave(Map<String, ?> parameters) {
    var result =
        messagesJpa.nextMessagePosition(
            (java.util.UUID) views.value(parameters, "c", java.util.UUID.class));
    return java.util.Map.of("n", result);
  }

  private int applyMessagesForSave(Map<String, ?> parameters) {
    var entity = new vn.edu.utc.rag.persistence.entity.MessagesEntity();
    entity.setId((UUID) views.value(parameters, "id", UUID.class));
    entity.setConversationId((UUID) views.value(parameters, "c", UUID.class));
    entity.setPosition((Integer) views.value(parameters, "p", Integer.class));
    entity.setAuthorType("USER");
    entity.setBody((String) views.value(parameters, "body", String.class));
    entity.setCreatedAt(java.time.Instant.now());
    var saved = messagesJpa.saveAndFlush(entity);
    return 1;
  }

  private int applyMessagesForSave2(Map<String, ?> parameters) {
    var entity = new vn.edu.utc.rag.persistence.entity.MessagesEntity();
    entity.setId((UUID) views.value(parameters, "id", UUID.class));
    entity.setConversationId((UUID) views.value(parameters, "c", UUID.class));
    entity.setPosition((Integer) views.value(parameters, "p", Integer.class));
    entity.setAuthorType("ASSISTANT");
    entity.setBody((String) views.value(parameters, "body", String.class));
    entity.setCreatedAt(java.time.Instant.now());
    entity.setAnswerStatus((String) views.value(parameters, "status", String.class));
    entity.setReplyToId((UUID) views.value(parameters, "reply", UUID.class));
    var saved = messagesJpa.saveAndFlush(entity);
    return 1;
  }

  private int applyMessageCitationsForSave(Map<String, ?> parameters) {
    var entity = new vn.edu.utc.rag.persistence.entity.MessageCitationsEntity();
    entity.setMessageId((UUID) views.value(parameters, "m", UUID.class));
    entity.setPosition((Short) views.value(parameters, "p", Short.class));
    entity.setChunkId((Long) views.value(parameters, "id", Long.class));
    entity.setSourceTitle((String) views.value(parameters, "title", String.class));
    entity.setSourceUrl((String) views.value(parameters, "url", String.class));
    entity.setSourceYear((Short) views.value(parameters, "year", Short.class));
    entity.setLocator((String) views.value(parameters, "locator", String.class));
    var saved = messageCitationsJpa.saveAndFlush(entity);
    return 1;
  }

  private int applyConversationsForSave(Map<String, ?> parameters) {
    var conversation =
        conversationsJpa
            .findById((UUID) parameters.get("id"))
            .orElseThrow(vn.edu.utc.rag.exception.ApiException::missing);
    conversation.setUpdatedAt(java.time.Instant.now());
    if (conversation.getDisplayTitle() == null)
      conversation.setDisplayTitle((String) parameters.get("title"));
    return 1;
  }
}
