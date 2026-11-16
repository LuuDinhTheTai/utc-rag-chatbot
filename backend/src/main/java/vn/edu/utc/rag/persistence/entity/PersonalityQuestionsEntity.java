package vn.edu.utc.rag.persistence.entity;

import jakarta.persistence.*;
import java.util.*;

@Entity
@Table(name = "personality_questions")
public class PersonalityQuestionsEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", columnDefinition = "bigint")
  private Long id;

  public Long getId() {
    return id;
  }

  public void setId(Long value) {
    this.id = value;
  }

  @Column(name = "questionnaire_version_id", columnDefinition = "bigint")
  private Long questionnaireVersionId;

  public Long getQuestionnaireVersionId() {
    return questionnaireVersionId;
  }

  public void setQuestionnaireVersionId(Long value) {
    this.questionnaireVersionId = value;
  }

  @Column(name = "position", columnDefinition = "smallint")
  private Short position;

  public Short getPosition() {
    return position;
  }

  public void setPosition(Short value) {
    this.position = value;
  }

  @Column(name = "body", columnDefinition = "text")
  private String body;

  public String getBody() {
    return body;
  }

  public void setBody(String value) {
    this.body = value;
  }
}
