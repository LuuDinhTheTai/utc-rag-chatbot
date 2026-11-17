package vn.edu.utc.rag.security;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional
public class AccessDataRepository {

  private final vn.edu.utc.rag.persistence.repository.ConversationsRepository conversations;

  private final vn.edu.utc.rag.persistence.repository.PersonalityAttemptsRepository attempts;

  public AccessDataRepository(
      vn.edu.utc.rag.persistence.repository.ConversationsRepository conversations,
      vn.edu.utc.rag.persistence.repository.PersonalityAttemptsRepository attempts,
      vn.edu.utc.rag.persistence.internal.EntityViews views) {
    this.conversations = conversations;
    this.attempts = attempts;
    this.views = views;
  }

  public Map<String, Object> findRecordsForOwn(Map<String, ?> parameters, String table) {
    UUID id = (UUID) parameters.get("id");
    Object entity =
        switch (table) {
          case "conversations" ->
              conversations.findById(id).orElseThrow(vn.edu.utc.rag.common.ApiException::missing);
          case "personality_attempts" ->
              attempts.findById(id).orElseThrow(vn.edu.utc.rag.common.ApiException::missing);
          default -> throw new IllegalArgumentException("Unsupported ownership resource");
        };
    return views.of(entity);
  }

  private final vn.edu.utc.rag.persistence.internal.EntityViews views;
}
