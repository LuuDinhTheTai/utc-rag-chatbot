package vn.edu.utc.rag.persistence.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.*;

@Entity
@Table(name = "admission_cutoffs")
public class AdmissionCutoffsEntity {

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

  @Column(name = "option_id", columnDefinition = "bigint")
  private Long optionId;

  public Long getOptionId() {
    return optionId;
  }

  public void setOptionId(Long value) {
    this.optionId = value;
  }

  @Column(name = "combination_id", columnDefinition = "smallint")
  private Short combinationId;

  public Short getCombinationId() {
    return combinationId;
  }

  public void setCombinationId(Short value) {
    this.combinationId = value;
  }

  @Column(name = "score", columnDefinition = "numeric")
  private BigDecimal score;

  public BigDecimal getScore() {
    return score;
  }

  public void setScore(BigDecimal value) {
    this.score = value;
  }

  @Column(name = "scale_code", columnDefinition = "text")
  private String scaleCode;

  public String getScaleCode() {
    return scaleCode;
  }

  public void setScaleCode(String value) {
    this.scaleCode = value;
  }

  @Column(name = "source_revision_id", columnDefinition = "bigint")
  private Long sourceRevisionId;

  public Long getSourceRevisionId() {
    return sourceRevisionId;
  }

  public void setSourceRevisionId(Long value) {
    this.sourceRevisionId = value;
  }
}
