package vn.edu.utc.rag.persistence.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.edu.utc.rag.persistence.entity.*;

public interface GuideEntriesRepository extends JpaRepository<GuideEntriesEntity, Long> {

  int deleteByGuideVersionId(Long v);

  java.util.List<GuideEntriesEntity> findAllByGuideVersionIdOrderByPositionAscIdAsc(Long v);
}
