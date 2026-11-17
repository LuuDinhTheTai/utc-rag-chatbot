package vn.edu.utc.rag.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "guide_versions")
public class GuideVersionsEntity {

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

  @Column(name = "guide_id", columnDefinition = "bigint")
  private Long guideId;

  public Long getGuideId() {
    return guideId;
  }

  public void setGuideId(Long value) {
    this.guideId = value;
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
