package vn.edu.utc.rag.persistence.entity;

import jakarta.persistence.*;
import java.util.*;

@Entity
@Table(name = "admission_option_combinations")
@IdClass(AdmissionOptionCombinationsEntityId.class)
public class AdmissionOptionCombinationsEntity {

  @Id
  @Column(name = "option_id", columnDefinition = "bigint")
  private Long optionId;

  public Long getOptionId() {
    return optionId;
  }

  public void setOptionId(Long value) {
    this.optionId = value;
  }

  @Id
  @Column(name = "combination_id", columnDefinition = "smallint")
  private Short combinationId;

  public Short getCombinationId() {
    return combinationId;
  }

  public void setCombinationId(Short value) {
    this.combinationId = value;
  }

  @Column(name = "conditions_text", columnDefinition = "text")
  private String conditionsText;

  public String getConditionsText() {
    return conditionsText;
  }

  public void setConditionsText(String value) {
    this.conditionsText = value;
  }
}
