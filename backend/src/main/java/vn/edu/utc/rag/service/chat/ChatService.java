package vn.edu.utc.rag.service.chat;

import jakarta.servlet.http.HttpServletRequest;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.utc.rag.dto.chat.ChatTurnResult;
import vn.edu.utc.rag.exception.ApiException;
import vn.edu.utc.rag.integration.ollama.OllamaClient;
import vn.edu.utc.rag.persistence.repository.query.ChatQueryRepository;
import vn.edu.utc.rag.security.*;
import vn.edu.utc.rag.validation.Input;
import vn.edu.utc.rag.web.pagination.PageRequestFactory;

@Service()
@Transactional()
public class ChatService {

  private final Access access;

  private final PageRequestFactory pages;

  private final ChatPersistenceService store;

  private final OllamaClient ai;

  private final RateLimits limits;

  public ChatService(
      Access access,
      PageRequestFactory pages,
      ChatPersistenceService store,
      OllamaClient ai,
      RateLimits limits,
      ChatQueryRepository repository,
      vn.edu.utc.rag.mapper.common.EntityMapper views,
      vn.edu.utc.rag.persistence.repository.ConversationsRepository conversationsJpa,
      vn.edu.utc.rag.persistence.repository.MessageCitationsRepository messageCitationsJpa,
      vn.edu.utc.rag.persistence.repository.MessagesRepository messagesJpa,
      vn.edu.utc.rag.persistence.repository.DocumentRevisionsRepository documentRevisionsJpa) {
    this.access = access;
    this.pages = pages;
    this.store = store;
    this.ai = ai;
    this.limits = limits;
    this.repository = repository;
    this.views = views;
    this.conversationsJpa = conversationsJpa;
    this.messageCitationsJpa = messageCitationsJpa;
    this.messagesJpa = messagesJpa;
    this.documentRevisionsJpa = documentRevisionsJpa;
  }

  public Map<String, Object> create(Map<String, Object> b, HttpServletRequest req) {
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
    var row = findConversationsForCreate(args);
    return row;
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
  public void delete(String id, HttpServletRequest req) {
    UUID c = Input.uuid(id);
    findConversationsForDelete(Map.of("id", c));
    access.own("conversations", c, req);
    applyMessageCitationsForDelete(Map.of("id", c));
    applyMessagesForDelete(Map.of("id", c));
    applyConversationsForDelete(Map.of("id", c));
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

  public ChatTurnResult send(String id, Map<String, Object> b, HttpServletRequest req) {
    UUID c = Input.uuid(id);
    access.own("conversations", c, req);
    Input.only(b, "client_message_id", "content");
    UUID client = Input.uuid(Input.text(b, "client_message_id"));
    String question = Input.text(b, "content");
    if (question.length() > 4000) throw ApiException.invalid("Question is too long.");
    var replay = store.replay(c, client, question);
    if (replay != null) return new ChatTurnResult(replay, true);
    limits.hit("chat:" + access.caller(req), 10, Duration.ofMinutes(1));
    var available = findDocumentRevisionsForSend(Map.of());
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
    return new ChatTurnResult(result.get("turn"), Boolean.TRUE.equals(result.get("replayed")));
  }

  @Transactional
  public void feedback(String id, String message, Map<String, Object> b, HttpServletRequest req) {
    UUID c = Input.uuid(id), m = Input.uuid(message);
    access.own("conversations", c, req);
    Input.only(b, "is_helpful", "comment_text");
    if (!(b.get("is_helpful") instanceof Boolean))
      throw ApiException.invalid("is_helpful is required.");
    findMessagesForFeedback(Map.of("id", m, "c", c));
    var args = new HashMap<String, Object>();
    args.put("id", m);
    args.put("helpful", b.get("is_helpful"));
    args.put("comment", b.get("comment_text") == null ? null : Input.text(b, "comment_text"));
    repository.applyChatFeedbackEntriesForFeedback(args);
  }

  private final ChatQueryRepository repository;

  private Map<String, Object> findConversationsForCreate(Map<String, ?> parameters) {
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

  private final vn.edu.utc.rag.mapper.common.EntityMapper views;

  private final vn.edu.utc.rag.persistence.repository.ConversationsRepository conversationsJpa;

  private Map<String, Object> findConversationsForDelete(Map<String, ?> parameters) {
    var result = conversationsJpa.findLockedById((UUID) views.value(parameters, "id", UUID.class));
    var rows = result.stream().map(e -> views.project(e, "id")).toList();
    if (rows.isEmpty()) throw vn.edu.utc.rag.exception.ApiException.missing();
    return rows.getFirst();
  }

  private int applyMessageCitationsForDelete(Map<String, ?> parameters) {
    return messageCitationsJpa.deleteByConversation((UUID) parameters.get("id"));
  }

  private final vn.edu.utc.rag.persistence.repository.MessageCitationsRepository
      messageCitationsJpa;

  private int applyMessagesForDelete(Map<String, ?> parameters) {
    {
      int count =
          messagesJpa.deleteByConversationId(
              (java.util.UUID) views.value(parameters, "id", java.util.UUID.class));
      messagesJpa.flush();
      return count;
    }
  }

  private final vn.edu.utc.rag.persistence.repository.MessagesRepository messagesJpa;

  private int applyConversationsForDelete(Map<String, ?> parameters) {
    {
      conversationsJpa.deleteById(
          (java.util.UUID) views.value(parameters, "id", java.util.UUID.class));
      conversationsJpa.flush();
      return 1;
    }
  }

  private Map<String, Object> findDocumentRevisionsForSend(Map<String, ?> parameters) {
    var result = documentRevisionsJpa.countPublishedVerifiedSources();
    return java.util.Map.of("n", result.toString());
  }

  private final vn.edu.utc.rag.persistence.repository.DocumentRevisionsRepository
      documentRevisionsJpa;

  private Map<String, Object> findMessagesForFeedback(Map<String, ?> parameters) {
    var result =
        messagesJpa.findAssistantByIdAndConversationId(
            (java.util.UUID) views.value(parameters, "id", java.util.UUID.class),
            (java.util.UUID) views.value(parameters, "c", java.util.UUID.class));
    var rows = result.stream().map(e -> views.project(e, "id")).toList();
    if (rows.isEmpty()) throw vn.edu.utc.rag.exception.ApiException.missing();
    return rows.getFirst();
  }
}
