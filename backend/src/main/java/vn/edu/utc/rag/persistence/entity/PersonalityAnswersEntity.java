package vn.edu.utc.rag.persistence.entity;

import jakarta.persistence.*;
import java.util.*;

@Entity
@Table(name = "personality_answers")
@IdClass(PersonalityAnswersEntityId.class)
public class PersonalityAnswersEntity {

  @Id
  @Column(name = "attempt_id", columnDefinition = "uuid")
  private UUID attemptId;

  public UUID getAttemptId() {
    return attemptId;
  }

  public void setAttemptId(UUID value) {
    this.attemptId = value;
  }

  @Id
  @Column(name = "question_id", columnDefinition = "bigint")
  private Long questionId;

  public Long getQuestionId() {
    return questionId;
  }

  public void setQuestionId(Long value) {
    this.questionId = value;
  }

  @Column(name = "option_id", columnDefinition = "bigint")
  private Long optionId;

  public Long getOptionId() {
    return optionId;
  }

  public void setOptionId(Long value) {
    this.optionId = value;
  }

  @Column(name = "questionnaire_version_id", columnDefinition = "bigint")
  private Long questionnaireVersionId;

  public Long getQuestionnaireVersionId() {
    return questionnaireVersionId;
  }

  public void setQuestionnaireVersionId(Long value) {
    this.questionnaireVersionId = value;
  }
}
