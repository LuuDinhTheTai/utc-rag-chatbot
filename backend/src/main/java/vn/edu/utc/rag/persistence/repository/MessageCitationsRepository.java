package vn.edu.utc.rag.persistence.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.edu.utc.rag.persistence.entity.*;

public interface MessageCitationsRepository
    extends JpaRepository<MessageCitationsEntity, MessageCitationsEntityId> {

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "delete from MessageCitationsEntity e where e.messageId in (select m.id from MessagesEntity m"
          + " where m.conversationId=:conversation)")
  int deleteByConversation(@Param("conversation") java.util.UUID conversation);

  java.util.List<MessageCitationsEntity> findAllByMessageIdOrderByPositionAsc(java.util.UUID id);
}
