package vn.edu.utc.rag.persistence.repository;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.edu.utc.rag.persistence.entity.*;

public interface MessagesRepository extends JpaRepository<MessagesEntity, UUID> {

  java.util.List<MessagesEntity> findAllByReplyToId(java.util.UUID id);

  @Query("SELECT coalesce(max(e.position),0)+1 FROM MessagesEntity e WHERE e.conversationId=:c")
  Integer nextMessagePosition(@Param("c") java.util.UUID c);

  // Delete the reply graph in one statement: per-entity deletes can violate reply_to_id.
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query("delete from MessagesEntity e where e.conversationId=:conversationId")
  int deleteByConversationId(@Param("conversationId") java.util.UUID id);

  default java.util.List<MessagesEntity> findAssistantByIdAndConversationId(
      java.util.UUID id, java.util.UUID c) {
    return findAllByIdAndConversationIdAndAuthorType(id, c, "ASSISTANT");
  }

  java.util.List<MessagesEntity> findAllByIdAndConversationId(java.util.UUID id, java.util.UUID c);

  java.util.List<MessagesEntity> findAllByIdAndConversationIdAndAuthorType(
      java.util.UUID id, java.util.UUID c, String authorTypeValue);
}
