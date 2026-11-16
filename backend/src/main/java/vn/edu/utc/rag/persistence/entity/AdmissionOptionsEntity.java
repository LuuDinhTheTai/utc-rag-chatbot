package vn.edu.utc.rag.persistence.entity;

import jakarta.persistence.*;
import java.util.*;

@Entity
@Table(name = "admission_options")
public class AdmissionOptionsEntity {
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

  @Column(name = "cycle_version_id", columnDefinition = "bigint")
  private Long cycleVersionId;

  public Long getCycleVersionId() {
    return cycleVersionId;
  }

  public void setCycleVersionId(Long value) {
    this.cycleVersionId = value;
  }

  @Column(name = "choice_id", columnDefinition = "bigint")
  private Long choiceId;

  public Long getChoiceId() {
    return choiceId;
  }

  public void setChoiceId(Long value) {
    this.choiceId = value;
  }

  @Column(name = "cycle_method_id", columnDefinition = "bigint")
  private Long cycleMethodId;

  public Long getCycleMethodId() {
    return cycleMethodId;
  }

  public void setCycleMethodId(Long value) {
    this.cycleMethodId = value;
  }

  @Column(name = "method_quota", columnDefinition = "integer")
  private Integer methodQuota;

  public Integer getMethodQuota() {
    return methodQuota;
  }

  public void setMethodQuota(Integer value) {
    this.methodQuota = value;
  }

  @Column(name = "additional_conditions", columnDefinition = "text")
  private String additionalConditions;

  public String getAdditionalConditions() {
    return additionalConditions;
  }

  public void setAdditionalConditions(String value) {
    this.additionalConditions = value;
  }
}
