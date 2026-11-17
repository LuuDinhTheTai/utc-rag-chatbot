package vn.edu.utc.rag.persistence.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.edu.utc.rag.persistence.entity.*;

public interface DocumentChunksRepository extends JpaRepository<DocumentChunksEntity, Long> {

  Long countByRevisionId(Long id);
}
