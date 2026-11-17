package vn.edu.utc.rag.persistence.repository;

import jakarta.persistence.LockModeType;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import vn.edu.utc.rag.persistence.entity.*;

public interface ConversationsRepository extends JpaRepository<ConversationsEntity, UUID> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  java.util.List<ConversationsEntity> findLockedById(java.util.UUID id);
}
