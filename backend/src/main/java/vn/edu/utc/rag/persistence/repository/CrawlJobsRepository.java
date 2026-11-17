package vn.edu.utc.rag.persistence.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.edu.utc.rag.persistence.entity.*;

public interface CrawlJobsRepository extends JpaRepository<CrawlJobsEntity, Long> {

  java.util.List<CrawlJobsEntity> findAllBySourceIdAndStatusIn(
      Long source, java.util.Collection<String> statuses);
}
