package vn.edu.utc.rag.persistence.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import vn.edu.utc.rag.persistence.entity.*;

public interface DocumentRevisionsRepository
    extends JpaRepository<DocumentRevisionsEntity, Long>,
        org.springframework.data.jpa.repository.JpaSpecificationExecutor<DocumentRevisionsEntity> {

  default java.util.List<DocumentRevisionsEntity> findPublishedVerifiedSourceForRead(Long id) {
    return findAllByIdAndStatusAndSourceVerifiedTrueAndRevokedAtIsNull(id, "PUBLISHED");
  }

  default Long countPublishedVerifiedSources() {
    return countByStatusAndSourceVerifiedTrueAndRevokedAtIsNull("PUBLISHED");
  }

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  java.util.List<DocumentRevisionsEntity> findLockedById(Long id);

  default java.util.List<DocumentRevisionsEntity> findPublishedByDocumentId(Long d) {
    return findAllByDocumentIdAndStatus(d, "PUBLISHED");
  }

  @Lock(LockModeType.PESSIMISTIC_READ)
  java.util.List<DocumentRevisionsEntity>
      findAllByIdAndStatusAndSourceVerifiedTrueAndRevokedAtIsNull(Long id, String statusValue);

  Long countByStatusAndSourceVerifiedTrueAndRevokedAtIsNull(String statusValue);

  java.util.List<DocumentRevisionsEntity> findAllByDocumentIdAndStatus(Long d, String statusValue);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  java.util.List<DocumentRevisionsEntity> findAllByIdAndDocumentId(Long v, Long d);

  @Lock(LockModeType.PESSIMISTIC_READ)
  java.util.List<DocumentRevisionsEntity> findAllByIdAndRevokedAtIsNullAndSourceVerifiedTrue(
      Long id);
}
