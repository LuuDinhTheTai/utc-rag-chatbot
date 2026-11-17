package vn.edu.utc.rag.persistence.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.edu.utc.rag.persistence.entity.*;

public interface AdmissionCutoffsRepository extends JpaRepository<AdmissionCutoffsEntity, Long> {

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "delete from AdmissionCutoffsEntity e where e.optionId in (select o.id from"
          + " AdmissionOptionsEntity o where o.cycleVersionId=:version)")
  int deleteByCycleVersion(@Param("version") Long version);

  @Query(
      "select c.sourceRevisionId from AdmissionCutoffsEntity c join AdmissionOptionsEntity o on"
          + " o.id=c.optionId where o.cycleVersionId=:version and c.sourceRevisionId is not null")
  java.util.List<Long> findSourceIdsByVersion(@Param("version") Long version);

  java.util.List<AdmissionCutoffsEntity> findAllByOptionIdOrderByIdAsc(Long id);
}
