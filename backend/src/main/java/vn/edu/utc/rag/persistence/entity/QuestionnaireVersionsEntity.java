package vn.edu.utc.rag.persistence.entity;

import jakarta.persistence.*;
import java.util.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "questionnaire_versions")
public class QuestionnaireVersionsEntity {

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

  @Column(name = "questionnaire_id", columnDefinition = "bigint")
  private Long questionnaireId;

  public Long getQuestionnaireId() {
    return questionnaireId;
  }

  public void setQuestionnaireId(Long value) {
    this.questionnaireId = value;
  }

  @Column(name = "version_no", columnDefinition = "integer")
  private Integer versionNo;

  public Integer getVersionNo() {
    return versionNo;
  }

  public void setVersionNo(Integer value) {
    this.versionNo = value;
  }

  @Column(name = "status", columnDefinition = "text")
  private String status;

  public String getStatus() {
    return status;
  }

  public void setStatus(String value) {
    this.status = value;
  }

  @Column(name = "instructions", columnDefinition = "text")
  private String instructions;

  public String getInstructions() {
    return instructions;
  }

  public void setInstructions(String value) {
    this.instructions = value;
  }

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "scoring_config", columnDefinition = "jsonb")
  private Map<String, Object> scoringConfig;

  public Map<String, Object> getScoringConfig() {
    return scoringConfig;
  }

  public void setScoringConfig(Map<String, Object> value) {
    this.scoringConfig = value;
  }
}
