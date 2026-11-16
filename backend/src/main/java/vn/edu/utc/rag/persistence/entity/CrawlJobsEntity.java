package vn.edu.utc.rag.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "crawl_jobs")
public class CrawlJobsEntity {
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

  @Column(name = "source_id", columnDefinition = "bigint")
  private Long sourceId;

  public Long getSourceId() {
    return sourceId;
  }

  public void setSourceId(Long value) {
    this.sourceId = value;
  }

  @Column(name = "requested_by", columnDefinition = "uuid")
  private UUID requestedBy;

  public UUID getRequestedBy() {
    return requestedBy;
  }

  public void setRequestedBy(UUID value) {
    this.requestedBy = value;
  }

  @Column(name = "status", columnDefinition = "text")
  private String status;

  public String getStatus() {
    return status;
  }

  public void setStatus(String value) {
    this.status = value;
  }

  @Column(name = "run_after", columnDefinition = "timestamptz")
  private Instant runAfter;

  public Instant getRunAfter() {
    return runAfter;
  }

  public void setRunAfter(Instant value) {
    this.runAfter = value;
  }

  @Column(name = "lease_until", columnDefinition = "timestamptz")
  private Instant leaseUntil;

  public Instant getLeaseUntil() {
    return leaseUntil;
  }

  public void setLeaseUntil(Instant value) {
    this.leaseUntil = value;
  }

  @Column(name = "claim_token", columnDefinition = "uuid")
  private UUID claimToken;

  public UUID getClaimToken() {
    return claimToken;
  }

  public void setClaimToken(UUID value) {
    this.claimToken = value;
  }

  @Column(name = "attempt_no", columnDefinition = "integer")
  private Integer attemptNo;

  public Integer getAttemptNo() {
    return attemptNo;
  }

  public void setAttemptNo(Integer value) {
    this.attemptNo = value;
  }
}
