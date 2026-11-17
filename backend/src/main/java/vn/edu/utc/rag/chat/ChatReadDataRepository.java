package vn.edu.utc.rag.chat;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.utc.rag.persistence.internal.NativeSqlExecutor;

@Repository
@Transactional
public class ChatReadDataRepository {

  private final NativeSqlExecutor query;

  public ChatReadDataRepository(
      NativeSqlExecutor query,
      vn.edu.utc.rag.persistence.internal.EntityViews views,
      vn.edu.utc.rag.persistence.repository.ConversationsRepository conversationsJpa,
      vn.edu.utc.rag.persistence.repository.DocumentRevisionsRepository documentRevisionsJpa,
      vn.edu.utc.rag.persistence.repository.MessagesRepository messagesJpa,
      vn.edu.utc.rag.persistence.query.QueryPages queryPages,
      vn.edu.utc.rag.persistence.repository.MessageCitationsRepository messageCitationsJpa) {
    this.query = query;
    this.views = views;
    this.conversationsJpa = conversationsJpa;
    this.documentRevisionsJpa = documentRevisionsJpa;
    this.messagesJpa = messagesJpa;
    this.queryPages = queryPages;
    this.messageCitationsJpa = messageCitationsJpa;
  }

  public Map<String, Object> findConversationsForCreate(Map<String, ?> parameters) {
    var entity = new vn.edu.utc.rag.persistence.entity.ConversationsEntity();
    entity.setOwnerUserId((UUID) views.value(parameters, "u", UUID.class));
    entity.setGuestTokenHash((String) views.value(parameters, "g", String.class));
    entity.setDisplayTitle((String) views.value(parameters, "title", String.class));
    entity.setExpiresAt((Instant) views.value(parameters, "expiry", Instant.class));
    entity.setCreatedAt(java.time.Instant.now());
    entity.setUpdatedAt(java.time.Instant.now());
    var saved = conversationsJpa.saveAndFlush(entity);
    return views.project(saved, "id,display_title,expires_at,created_at,updated_at");
  }

  public Map<String, Object> findConversationsForDelete(Map<String, ?> parameters) {
    var result = conversationsJpa.findLockedById((UUID) views.value(parameters, "id", UUID.class));
    var rows = result.stream().map(e -> views.project(e, "id")).toList();
    if (rows.isEmpty()) throw vn.edu.utc.rag.common.ApiException.missing();
    return rows.getFirst();
  }

  public int applyMessageCitationsForDelete(Map<String, ?> parameters) {
    return messageCitationsJpa.deleteByConversation((UUID) parameters.get("id"));
  }

  public int applyMessagesForDelete(Map<String, ?> parameters) {
    {
      int count =
          messagesJpa.deleteByConversationId(
              (java.util.UUID) views.value(parameters, "id", java.util.UUID.class));
      messagesJpa.flush();
      return count;
    }
  }

  public int applyConversationsForDelete(Map<String, ?> parameters) {
    {
      conversationsJpa.deleteById(
          (java.util.UUID) views.value(parameters, "id", java.util.UUID.class));
      conversationsJpa.flush();
      return 1;
    }
  }

  public Map<String, Object> findDocumentRevisionsForSend(Map<String, ?> parameters) {
    var result = documentRevisionsJpa.countPublishedVerifiedSources();
    return java.util.Map.of("n", result.toString());
  }

  public List<Map<String, Object>> findDocumentChunksForSend(Map<String, ?> parameters) {
    return query.list(
        "SELECT ch.id,ch.revision_id,left(ch.body,3000) AS"
            + " body,ch.locator,r.title,r.official_url,r.admission_year FROM document_chunks"
            + " ch JOIN document_revisions r ON r.id=ch.revision_id WHERE"
            + " r.status='PUBLISHED' AND r.source_verified AND r.revoked_at IS NULL AND"
            + " (ch.embedding <=> CAST(:v AS vector))<0.6 ORDER BY ch.embedding <=> CAST(:v"
            + " AS vector),ch.id LIMIT 3",
        parameters);
  }

  public Map<String, Object> findMessagesForFeedback(Map<String, ?> parameters) {
    var result =
        messagesJpa.findAssistantByIdAndConversationId(
            (java.util.UUID) views.value(parameters, "id", java.util.UUID.class),
            (java.util.UUID) views.value(parameters, "c", java.util.UUID.class));
    var rows = result.stream().map(e -> views.project(e, "id")).toList();
    if (rows.isEmpty()) throw vn.edu.utc.rag.common.ApiException.missing();
    return rows.getFirst();
  }

  public int applyChatFeedbackEntriesForFeedback(Map<String, ?> parameters) {
    return query.update(
        "INSERT INTO chat_feedback_entries(answer_id,message_id,is_helpful,comment_text,created_at)"
            + " VALUES(:id,:id,:helpful,:comment,now()) ON CONFLICT(answer_id) DO UPDATE SET"
            + " is_helpful=excluded.is_helpful,comment_text=excluded.comment_text",
        parameters);
  }

  private final vn.edu.utc.rag.persistence.internal.EntityViews views;

  private final vn.edu.utc.rag.persistence.repository.ConversationsRepository conversationsJpa;

  private final vn.edu.utc.rag.persistence.repository.DocumentRevisionsRepository
      documentRevisionsJpa;

  private final vn.edu.utc.rag.persistence.repository.MessagesRepository messagesJpa;

  private final vn.edu.utc.rag.persistence.query.QueryPages queryPages;

  public Map<String, Object> findPageForHistory(
      Map<String, ?> input, vn.edu.utc.rag.persistence.query.QueryPage req) {
    Map<String, Object> parameters = new HashMap<>(input);
    String filter = "";
    if (req.getParameter("q") != null) {
      filter =
          " AND (display_title ILIKE :q OR EXISTS(SELECT 1 FROM messages m WHERE"
              + " m.conversation_id=c.id AND m.body ILIKE :q))";
      parameters.put("q", "%" + req.getParameter("q") + "%");
    }
    return queryPages.page(
        "SELECT id,display_title,created_at,updated_at FROM conversations c WHERE owner_user_id=:u"
            + filter,
        parameters,
        req,
        "updated_at:timestamptz:desc",
        "id:uuid:desc");
  }

  public Map<String, Object> findPageForMessages(
      Map<String, ?> input, vn.edu.utc.rag.persistence.query.QueryPage req) {
    Map<String, Object> parameters = new HashMap<>(input);
    return queryPages.page(
        "SELECT id,position FROM messages WHERE conversation_id=:id",
        parameters,
        req,
        "position:integer:asc",
        "id:uuid:asc");
  }

  private final vn.edu.utc.rag.persistence.repository.MessageCitationsRepository
      messageCitationsJpa;
}
