package vn.edu.utc.rag.persistence.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.*;

@Entity
@Table(name = "score_rule_bands")
public class ScoreRuleBandsEntity {
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

  @Column(name = "rule_input_id", columnDefinition = "bigint")
  private Long ruleInputId;

  public Long getRuleInputId() {
    return ruleInputId;
  }

  public void setRuleInputId(Long value) {
    this.ruleInputId = value;
  }

  @Column(name = "match_code", columnDefinition = "text")
  private String matchCode;

  public String getMatchCode() {
    return matchCode;
  }

  public void setMatchCode(String value) {
    this.matchCode = value;
  }

  @Column(name = "lower_bound", columnDefinition = "numeric")
  private BigDecimal lowerBound;

  public BigDecimal getLowerBound() {
    return lowerBound;
  }

  public void setLowerBound(BigDecimal value) {
    this.lowerBound = value;
  }

  @Column(name = "upper_bound", columnDefinition = "numeric")
  private BigDecimal upperBound;

  public BigDecimal getUpperBound() {
    return upperBound;
  }

  public void setUpperBound(BigDecimal value) {
    this.upperBound = value;
  }

  @Column(name = "converted_value", columnDefinition = "numeric")
  private BigDecimal convertedValue;

  public BigDecimal getConvertedValue() {
    return convertedValue;
  }

  public void setConvertedValue(BigDecimal value) {
    this.convertedValue = value;
  }
}
