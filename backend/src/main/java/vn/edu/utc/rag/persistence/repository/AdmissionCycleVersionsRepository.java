package vn.edu.utc.rag.persistence.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.edu.utc.rag.persistence.entity.*;

public interface AdmissionCycleVersionsRepository
    extends JpaRepository<AdmissionCycleVersionsEntity, Long> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  java.util.Optional<AdmissionCycleVersionsEntity> findLockedById(Long id);

  java.util.List<AdmissionCycleVersionsEntity> findAllByCycleIdAndStatus(
      Long parentId, String status);

  @Query(
      "select coalesce(max(e.versionNo),0)+1 from AdmissionCycleVersionsEntity e where"
          + " e.cycleId=:parent")
  Integer nextVersionNumber(@Param("parent") Long parentId);
}
