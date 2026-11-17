package vn.edu.utc.rag.persistence.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.edu.utc.rag.persistence.entity.*;

public interface ScheduleEventsRepository extends JpaRepository<ScheduleEventsEntity, Long> {

  java.util.List<ScheduleEventsEntity> findAllByCycleVersionIdOrderByStartsAtAscIdAsc(Long version);

  java.util.List<ScheduleEventsEntity> findAllByGuideVersionIdOrderByStartsAtAscIdAsc(Long version);

  void deleteByCycleVersionId(Long version);

  void deleteByGuideVersionId(Long version);
}
