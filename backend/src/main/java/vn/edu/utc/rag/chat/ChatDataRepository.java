package vn.edu.utc.rag.chat;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional
public class ChatDataRepository {

  public ChatDataRepository(
      vn.edu.utc.rag.persistence.internal.EntityViews views,
      vn.edu.utc.rag.persistence.repository.ConversationsRepository conversationsJpa,
      vn.edu.utc.rag.persistence.repository.DocumentRevisionsRepository documentRevisionsJpa,
      vn.edu.utc.rag.persistence.repository.MessageCitationsRepository messageCitationsJpa,
      vn.edu.utc.rag.persistence.repository.MessagesRepository messagesJpa) {
    this.views = views;
    this.conversationsJpa = conversationsJpa;
    this.documentRevisionsJpa = documentRevisionsJpa;
    this.messageCitationsJpa = messageCitationsJpa;
    this.messagesJpa = messagesJpa;
  }

  public Map<String, Object> findMessagesForMessage(Map<String, ?> parameters) {
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
    if (rows.isEmpty()) throw vn.edu.utc.rag.common.ApiException.missing();
    return rows.getFirst();
  }

  public List<Map<String, Object>> findMessageCitationsForMessage(Map<String, ?> parameters) {
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

  public List<Map<String, Object>> findMessagesForReplay(Map<String, ?> parameters) {
    var result =
        messagesJpa.findById((java.util.UUID) views.value(parameters, "id", java.util.UUID.class));
    var rows =
        result.stream().map(e -> views.project(e, "id,conversation_id,body,author_type")).toList();
    return rows;
  }

  public Map<String, Object> findMessagesForReplay2(Map<String, ?> parameters) {
    var result =
        messagesJpa.findAllByReplyToId(
            (java.util.UUID) views.value(parameters, "id", java.util.UUID.class));
    var rows = result.stream().map(e -> views.project(e, "id")).toList();
    if (rows.isEmpty()) throw vn.edu.utc.rag.common.ApiException.missing();
    return rows.getFirst();
  }

  public Map<String, Object> findConversationsForSave(Map<String, ?> parameters) {
    var result =
        conversationsJpa.findLockedById(
            (java.util.UUID) views.value(parameters, "id", java.util.UUID.class));
    var rows = result.stream().map(e -> views.project(e, "id")).toList();
    if (rows.isEmpty()) throw vn.edu.utc.rag.common.ApiException.missing();
    return rows.getFirst();
  }

  public Map<String, Object> findDocumentRevisionsForSave(Map<String, ?> parameters) {
    var result =
        documentRevisionsJpa.findPublishedVerifiedSourceForRead(
            (Long) views.value(parameters, "id", Long.class));
    var rows = result.stream().map(e -> views.project(e, "r.id")).toList();
    if (rows.isEmpty()) throw vn.edu.utc.rag.common.ApiException.missing();
    return rows.getFirst();
  }

  public Map<String, Object> findMessagesForSave(Map<String, ?> parameters) {
    var result =
        messagesJpa.nextMessagePosition(
            (java.util.UUID) views.value(parameters, "c", java.util.UUID.class));
    return java.util.Map.of("n", result);
  }

  public int applyMessagesForSave(Map<String, ?> parameters) {
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

  public int applyMessagesForSave2(Map<String, ?> parameters) {
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

  public int applyMessageCitationsForSave(Map<String, ?> parameters) {
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

  public int applyConversationsForSave(Map<String, ?> parameters) {
    var conversation =
        conversationsJpa
            .findById((UUID) parameters.get("id"))
            .orElseThrow(vn.edu.utc.rag.common.ApiException::missing);
    conversation.setUpdatedAt(java.time.Instant.now());
    if (conversation.getDisplayTitle() == null)
      conversation.setDisplayTitle((String) parameters.get("title"));
    return 1;
  }

  private final vn.edu.utc.rag.persistence.internal.EntityViews views;

  private final vn.edu.utc.rag.persistence.repository.ConversationsRepository conversationsJpa;

  private final vn.edu.utc.rag.persistence.repository.DocumentRevisionsRepository
      documentRevisionsJpa;

  private final vn.edu.utc.rag.persistence.repository.MessageCitationsRepository
      messageCitationsJpa;

  private final vn.edu.utc.rag.persistence.repository.MessagesRepository messagesJpa;
}
