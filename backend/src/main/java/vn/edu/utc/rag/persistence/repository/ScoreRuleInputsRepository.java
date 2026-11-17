package vn.edu.utc.rag.persistence.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.edu.utc.rag.persistence.entity.*;

public interface ScoreRuleInputsRepository extends JpaRepository<ScoreRuleInputsEntity, Long> {

  int deleteByRuleVersionId(Long v);

  java.util.List<ScoreRuleInputsEntity> findAllByRuleVersionIdOrderByIdAsc(Long v);
}
