package vn.edu.utc.rag.persistence.entity;

import jakarta.persistence.*;
import java.util.*;

@Entity
@Table(name = "admission_choices")
public class AdmissionChoicesEntity {
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

  @Column(name = "program_offering_id", columnDefinition = "bigint")
  private Long programOfferingId;

  public Long getProgramOfferingId() {
    return programOfferingId;
  }

  public void setProgramOfferingId(Long value) {
    this.programOfferingId = value;
  }

  @Column(name = "total_quota", columnDefinition = "integer")
  private Integer totalQuota;

  public Integer getTotalQuota() {
    return totalQuota;
  }

  public void setTotalQuota(Integer value) {
    this.totalQuota = value;
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
