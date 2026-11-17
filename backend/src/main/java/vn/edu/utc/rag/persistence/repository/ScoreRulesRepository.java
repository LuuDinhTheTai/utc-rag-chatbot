package vn.edu.utc.rag.persistence.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import vn.edu.utc.rag.persistence.entity.*;

public interface ScoreRulesRepository extends JpaRepository<ScoreRulesEntity, Long> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  java.util.Optional<ScoreRulesEntity> findLockedById(Long id);
}
