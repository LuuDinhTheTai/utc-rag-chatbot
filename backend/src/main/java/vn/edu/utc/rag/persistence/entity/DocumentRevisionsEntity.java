package vn.edu.utc.rag.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "document_revisions")
public class DocumentRevisionsEntity {

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

  @Column(name = "document_id", columnDefinition = "bigint")
  private Long documentId;

  public Long getDocumentId() {
    return documentId;
  }

  public void setDocumentId(Long value) {
    this.documentId = value;
  }

  @Column(name = "crawl_job_id", columnDefinition = "bigint")
  private Long crawlJobId;

  public Long getCrawlJobId() {
    return crawlJobId;
  }

  public void setCrawlJobId(Long value) {
    this.crawlJobId = value;
  }

  @Column(name = "uploaded_by", columnDefinition = "uuid")
  private UUID uploadedBy;

  public UUID getUploadedBy() {
    return uploadedBy;
  }

  public void setUploadedBy(UUID value) {
    this.uploadedBy = value;
  }

  @Column(name = "reviewed_by", columnDefinition = "uuid")
  private UUID reviewedBy;

  public UUID getReviewedBy() {
    return reviewedBy;
  }

  public void setReviewedBy(UUID value) {
    this.reviewedBy = value;
  }

  @Column(name = "campus_id", columnDefinition = "smallint")
  private Short campusId;

  public Short getCampusId() {
    return campusId;
  }

  public void setCampusId(Short value) {
    this.campusId = value;
  }

  @Column(name = "admission_year", columnDefinition = "smallint")
  private Short admissionYear;

  public Short getAdmissionYear() {
    return admissionYear;
  }

  public void setAdmissionYear(Short value) {
    this.admissionYear = value;
  }

  @Column(name = "title", columnDefinition = "text")
  private String title;

  public String getTitle() {
    return title;
  }

  public void setTitle(String value) {
    this.title = value;
  }

  @Column(name = "official_url", columnDefinition = "text")
  private String officialUrl;

  public String getOfficialUrl() {
    return officialUrl;
  }

  public void setOfficialUrl(String value) {
    this.officialUrl = value;
  }

  @Column(name = "storage_key", columnDefinition = "text")
  private String storageKey;

  public String getStorageKey() {
    return storageKey;
  }

  public void setStorageKey(String value) {
    this.storageKey = value;
  }

  @Column(name = "content_hash", columnDefinition = "text")
  private String contentHash;

  public String getContentHash() {
    return contentHash;
  }

  public void setContentHash(String value) {
    this.contentHash = value;
  }

  @Column(name = "pipeline_key", columnDefinition = "text")
  private String pipelineKey;

  public String getPipelineKey() {
    return pipelineKey;
  }

  public void setPipelineKey(String value) {
    this.pipelineKey = value;
  }

  @Column(name = "embedding_model", columnDefinition = "text")
  private String embeddingModel;

  public String getEmbeddingModel() {
    return embeddingModel;
  }

  public void setEmbeddingModel(String value) {
    this.embeddingModel = value;
  }

  @Column(name = "status", columnDefinition = "text")
  private String status;

  public String getStatus() {
    return status;
  }

  public void setStatus(String value) {
    this.status = value;
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

  @Column(name = "source_verified", columnDefinition = "boolean")
  private Boolean sourceVerified = false;

  public Boolean getSourceVerified() {
    return sourceVerified;
  }

  public void setSourceVerified(Boolean value) {
    this.sourceVerified = value;
  }

  @Column(name = "revoked_at", columnDefinition = "timestamptz")
  private Instant revokedAt;

  public Instant getRevokedAt() {
    return revokedAt;
  }

  public void setRevokedAt(Instant value) {
    this.revokedAt = value;
  }

  @Column(name = "revocation_reason", columnDefinition = "text")
  private String revocationReason;

  public String getRevocationReason() {
    return revocationReason;
  }

  public void setRevocationReason(String value) {
    this.revocationReason = value;
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
