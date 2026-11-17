package vn.edu.utc.rag.persistence.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.edu.utc.rag.persistence.entity.*;

public interface PersonalityOptionsRepository
    extends JpaRepository<PersonalityOptionsEntity, Long> {

  @Query(
      "select o from PersonalityOptionsEntity o join PersonalityAnswersEntity a on a.optionId=o.id"
          + " where a.attemptId=:attempt")
  java.util.List<PersonalityOptionsEntity> findAnsweredOptions(
      @Param("attempt") java.util.UUID attempt);
}
