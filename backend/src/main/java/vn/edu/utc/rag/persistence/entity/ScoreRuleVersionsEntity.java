package vn.edu.utc.rag.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "score_rule_versions")
public class ScoreRuleVersionsEntity {

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

  @Column(name = "score_rule_id", columnDefinition = "bigint")
  private Long scoreRuleId;

  public Long getScoreRuleId() {
    return scoreRuleId;
  }

  public void setScoreRuleId(Long value) {
    this.scoreRuleId = value;
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

  @Column(name = "formula_key", columnDefinition = "text")
  private String formulaKey;

  public String getFormulaKey() {
    return formulaKey;
  }

  public void setFormulaKey(String value) {
    this.formulaKey = value;
  }

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "formula_config", columnDefinition = "jsonb")
  private Map<String, Object> formulaConfig;

  public Map<String, Object> getFormulaConfig() {
    return formulaConfig;
  }

  public void setFormulaConfig(Map<String, Object> value) {
    this.formulaConfig = value;
  }

  @Column(name = "output_scale_code", columnDefinition = "text")
  private String outputScaleCode;

  public String getOutputScaleCode() {
    return outputScaleCode;
  }

  public void setOutputScaleCode(String value) {
    this.outputScaleCode = value;
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
