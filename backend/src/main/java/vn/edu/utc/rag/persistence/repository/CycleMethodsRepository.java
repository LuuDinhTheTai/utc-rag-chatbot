package vn.edu.utc.rag.persistence.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.edu.utc.rag.persistence.entity.*;

public interface CycleMethodsRepository extends JpaRepository<CycleMethodsEntity, Long> {

  int deleteByCycleVersionId(Long v);

  @Query(
      "select e.sourceRevisionId from CycleMethodsEntity e where e.cycleVersionId=:version and"
          + " e.sourceRevisionId is not null")
  java.util.List<Long> findSourceIdsByVersion(@Param("version") Long version);

  java.util.List<CycleMethodsEntity> findAllByCycleVersionIdOrderByIdAsc(Long v);
}
