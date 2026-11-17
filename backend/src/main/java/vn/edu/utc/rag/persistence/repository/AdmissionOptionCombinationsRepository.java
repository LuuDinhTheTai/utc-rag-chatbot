package vn.edu.utc.rag.persistence.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.edu.utc.rag.persistence.entity.*;

public interface AdmissionOptionCombinationsRepository
    extends JpaRepository<AdmissionOptionCombinationsEntity, AdmissionOptionCombinationsEntityId> {

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "delete from AdmissionOptionCombinationsEntity e where e.optionId in (select o.id from"
          + " AdmissionOptionsEntity o where o.cycleVersionId=:version)")
  int deleteByCycleVersion(@Param("version") Long version);

  java.util.List<AdmissionOptionCombinationsEntity> findAllByOptionIdOrderByCombinationIdAsc(
      Long id);
}
