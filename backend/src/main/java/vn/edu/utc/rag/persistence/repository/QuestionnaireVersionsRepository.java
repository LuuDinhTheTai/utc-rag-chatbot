package vn.edu.utc.rag.persistence.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.edu.utc.rag.persistence.entity.*;

public interface QuestionnaireVersionsRepository
    extends JpaRepository<QuestionnaireVersionsEntity, Long> {

  default java.util.List<QuestionnaireVersionsEntity> findPublishedByIdAndQuestionnaireId(
      Long v, Long q) {
    return findAllByIdAndQuestionnaireIdAndStatus(v, q, "PUBLISHED");
  }

  default java.util.List<QuestionnaireVersionsEntity> findPublishedById(Long id) {
    return findAllByIdAndStatus(id, "PUBLISHED");
  }

  java.util.List<QuestionnaireVersionsEntity> findAllByIdAndQuestionnaireIdAndStatus(
      Long v, Long q, String statusValue);

  java.util.List<QuestionnaireVersionsEntity> findAllByIdAndStatus(Long id, String statusValue);
}
