package vn.edu.utc.rag.persistence.entity;

import jakarta.persistence.*;
import java.util.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "personality_options")
public class PersonalityOptionsEntity {
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

  @Column(name = "question_id", columnDefinition = "bigint")
  private Long questionId;

  public Long getQuestionId() {
    return questionId;
  }

  public void setQuestionId(Long value) {
    this.questionId = value;
  }

  @Column(name = "position", columnDefinition = "smallint")
  private Short position;

  public Short getPosition() {
    return position;
  }

  public void setPosition(Short value) {
    this.position = value;
  }

  @Column(name = "label", columnDefinition = "text")
  private String label;

  public String getLabel() {
    return label;
  }

  public void setLabel(String value) {
    this.label = value;
  }

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "score_weights", columnDefinition = "jsonb")
  private Map<String, Object> scoreWeights;

  public Map<String, Object> getScoreWeights() {
    return scoreWeights;
  }

  public void setScoreWeights(Map<String, Object> value) {
    this.scoreWeights = value;
  }
}
