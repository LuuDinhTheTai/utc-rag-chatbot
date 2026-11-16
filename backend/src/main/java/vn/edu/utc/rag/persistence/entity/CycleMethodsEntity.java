package vn.edu.utc.rag.persistence.entity;

import jakarta.persistence.*;
import java.util.*;

@Entity
@Table(name = "cycle_methods")
public class CycleMethodsEntity {
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

  @Column(name = "method_id", columnDefinition = "smallint")
  private Short methodId;

  public Short getMethodId() {
    return methodId;
  }

  public void setMethodId(Short value) {
    this.methodId = value;
  }

  @Column(name = "eligibility_text", columnDefinition = "text")
  private String eligibilityText;

  public String getEligibilityText() {
    return eligibilityText;
  }

  public void setEligibilityText(String value) {
    this.eligibilityText = value;
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
