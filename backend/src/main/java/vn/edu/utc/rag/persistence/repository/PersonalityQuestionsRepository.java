package vn.edu.utc.rag.persistence.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.edu.utc.rag.persistence.entity.*;

public interface PersonalityQuestionsRepository
    extends JpaRepository<PersonalityQuestionsEntity, Long> {

  @Query(
      "select q from PersonalityQuestionsEntity q join PersonalityOptionsEntity o on"
          + " o.questionId=q.id where q.id=:question and o.id=:option and"
          + " q.questionnaireVersionId=:version")
  java.util.List<PersonalityQuestionsEntity> findMatchingOption(
      @Param("question") Long question,
      @Param("option") Long option,
      @Param("version") Long version);

  Long countByQuestionnaireVersionId(Long versionId);
}
