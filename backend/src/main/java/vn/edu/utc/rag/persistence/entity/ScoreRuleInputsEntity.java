package vn.edu.utc.rag.persistence.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.*;

@Entity
@Table(name = "score_rule_inputs")
public class ScoreRuleInputsEntity {
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

  @Column(name = "rule_version_id", columnDefinition = "bigint")
  private Long ruleVersionId;

  public Long getRuleVersionId() {
    return ruleVersionId;
  }

  public void setRuleVersionId(Long value) {
    this.ruleVersionId = value;
  }

  @Column(name = "input_kind", columnDefinition = "text")
  private String inputKind;

  public String getInputKind() {
    return inputKind;
  }

  public void setInputKind(String value) {
    this.inputKind = value;
  }

  @Column(name = "input_code", columnDefinition = "text")
  private String inputCode;

  public String getInputCode() {
    return inputCode;
  }

  public void setInputCode(String value) {
    this.inputCode = value;
  }

  @Column(name = "label", columnDefinition = "text")
  private String label;

  public String getLabel() {
    return label;
  }

  public void setLabel(String value) {
    this.label = value;
  }

  @Column(name = "is_required", columnDefinition = "boolean")
  private Boolean isRequired;

  public Boolean getIsRequired() {
    return isRequired;
  }

  public void setIsRequired(Boolean value) {
    this.isRequired = value;
  }

  @Column(name = "min_value", columnDefinition = "numeric")
  private BigDecimal minValue;

  public BigDecimal getMinValue() {
    return minValue;
  }

  public void setMinValue(BigDecimal value) {
    this.minValue = value;
  }

  @Column(name = "max_value", columnDefinition = "numeric")
  private BigDecimal maxValue;

  public BigDecimal getMaxValue() {
    return maxValue;
  }

  public void setMaxValue(BigDecimal value) {
    this.maxValue = value;
  }
}
