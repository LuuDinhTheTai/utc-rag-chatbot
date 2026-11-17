package vn.edu.utc.rag.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "curriculum_versions")
public class CurriculumVersionsEntity {

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

  @Column(name = "program_offering_id", columnDefinition = "bigint")
  private Long programOfferingId;

  public Long getProgramOfferingId() {
    return programOfferingId;
  }

  public void setProgramOfferingId(Long value) {
    this.programOfferingId = value;
  }

  @Column(name = "version_no", columnDefinition = "integer")
  private Integer versionNo;

  public Integer getVersionNo() {
    return versionNo;
  }

  public void setVersionNo(Integer value) {
    this.versionNo = value;
  }

  @Column(name = "duration_months", columnDefinition = "smallint")
  private Short durationMonths;

  public Short getDurationMonths() {
    return durationMonths;
  }

  public void setDurationMonths(Short value) {
    this.durationMonths = value;
  }

  @Column(name = "total_credits", columnDefinition = "smallint")
  private Short totalCredits;

  public Short getTotalCredits() {
    return totalCredits;
  }

  public void setTotalCredits(Short value) {
    this.totalCredits = value;
  }

  @Column(name = "objectives", columnDefinition = "text")
  private String objectives;

  public String getObjectives() {
    return objectives;
  }

  public void setObjectives(String value) {
    this.objectives = value;
  }

  @Column(name = "learning_outcomes", columnDefinition = "text")
  private String learningOutcomes;

  public String getLearningOutcomes() {
    return learningOutcomes;
  }

  public void setLearningOutcomes(String value) {
    this.learningOutcomes = value;
  }

  @Column(name = "career_paths", columnDefinition = "text")
  private String careerPaths;

  public String getCareerPaths() {
    return careerPaths;
  }

  public void setCareerPaths(String value) {
    this.careerPaths = value;
  }

  @Column(name = "status", columnDefinition = "text")
  private String status;

  public String getStatus() {
    return status;
  }

  public void setStatus(String value) {
    this.status = value;
  }

  @Column(name = "source_revision_id", columnDefinition = "bigint")
  private Long sourceRevisionId;

  public Long getSourceRevisionId() {
    return sourceRevisionId;
  }

  public void setSourceRevisionId(Long value) {
    this.sourceRevisionId = value;
  }

  @Column(name = "reviewed_by", columnDefinition = "uuid")
  private UUID reviewedBy;

  public UUID getReviewedBy() {
    return reviewedBy;
  }

  public void setReviewedBy(UUID value) {
    this.reviewedBy = value;
  }

  @Column(name = "published_at", columnDefinition = "timestamptz")
  private Instant publishedAt;

  public Instant getPublishedAt() {
    return publishedAt;
  }

  public void setPublishedAt(Instant value) {
    this.publishedAt = value;
  }

  @Column(name = "updated_at", columnDefinition = "timestamptz")
  private Instant updatedAt;

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Instant value) {
    this.updatedAt = value;
  }

  @Column(name = "lock_version", columnDefinition = "bigint")
  private Long lockVersion = 0L;

  public Long getLockVersion() {
    return lockVersion;
  }

  public void setLockVersion(Long value) {
    this.lockVersion = value;
  }
}
