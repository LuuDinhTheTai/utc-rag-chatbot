package vn.edu.utc.rag.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "financial_policy_versions")
public class FinancialPolicyVersionsEntity {
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

  @Column(name = "policy_id", columnDefinition = "bigint")
  private Long policyId;

  public Long getPolicyId() {
    return policyId;
  }

  public void setPolicyId(Long value) {
    this.policyId = value;
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

  @Column(name = "title", columnDefinition = "text")
  private String title;

  public String getTitle() {
    return title;
  }

  public void setTitle(String value) {
    this.title = value;
  }

  @Column(name = "amount_vnd", columnDefinition = "bigint")
  private Long amountVnd;

  public Long getAmountVnd() {
    return amountVnd;
  }

  public void setAmountVnd(Long value) {
    this.amountVnd = value;
  }

  @Column(name = "charge_unit", columnDefinition = "text")
  private String chargeUnit;

  public String getChargeUnit() {
    return chargeUnit;
  }

  public void setChargeUnit(String value) {
    this.chargeUnit = value;
  }

  @Column(name = "eligibility_text", columnDefinition = "text")
  private String eligibilityText;

  public String getEligibilityText() {
    return eligibilityText;
  }

  public void setEligibilityText(String value) {
    this.eligibilityText = value;
  }

  @Column(name = "due_at", columnDefinition = "timestamptz")
  private Instant dueAt;

  public Instant getDueAt() {
    return dueAt;
  }

  public void setDueAt(Instant value) {
    this.dueAt = value;
  }

  @Column(name = "body", columnDefinition = "text")
  private String body;

  public String getBody() {
    return body;
  }

  public void setBody(String value) {
    this.body = value;
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
