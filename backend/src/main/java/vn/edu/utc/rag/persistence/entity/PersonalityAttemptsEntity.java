package vn.edu.utc.rag.persistence.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "personality_attempts")
public class PersonalityAttemptsEntity
    implements org.springframework.data.domain.Persistable<UUID> {

  @Id
  @Column(name = "id", columnDefinition = "uuid")
  private UUID id = UUID.randomUUID();

  public UUID getId() {
    return id;
  }

  public void setId(UUID value) {
    this.id = value;
  }

  @Column(name = "questionnaire_version_id", columnDefinition = "bigint")
  private Long questionnaireVersionId;

  public Long getQuestionnaireVersionId() {
    return questionnaireVersionId;
  }

  public void setQuestionnaireVersionId(Long value) {
    this.questionnaireVersionId = value;
  }

  @Column(name = "owner_user_id", columnDefinition = "uuid")
  private UUID ownerUserId;

  public UUID getOwnerUserId() {
    return ownerUserId;
  }

  public void setOwnerUserId(UUID value) {
    this.ownerUserId = value;
  }

  @Column(name = "guest_token_hash", columnDefinition = "text")
  private String guestTokenHash;

  public String getGuestTokenHash() {
    return guestTokenHash;
  }

  public void setGuestTokenHash(String value) {
    this.guestTokenHash = value;
  }

  @Column(name = "status", columnDefinition = "text")
  private String status;

  public String getStatus() {
    return status;
  }

  public void setStatus(String value) {
    this.status = value;
  }

  @Column(name = "profile_id", columnDefinition = "bigint")
  private Long profileId;

  public Long getProfileId() {
    return profileId;
  }

  public void setProfileId(Long value) {
    this.profileId = value;
  }

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "score_summary", columnDefinition = "jsonb")
  private Map<String, Object> scoreSummary;

  public Map<String, Object> getScoreSummary() {
    return scoreSummary;
  }

  public void setScoreSummary(Map<String, Object> value) {
    this.scoreSummary = value;
  }

  @Column(name = "created_at", columnDefinition = "timestamptz")
  private Instant createdAt;

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant value) {
    this.createdAt = value;
  }

  @Column(name = "completed_at", columnDefinition = "timestamptz")
  private Instant completedAt;

  public Instant getCompletedAt() {
    return completedAt;
  }

  public void setCompletedAt(Instant value) {
    this.completedAt = value;
  }

  @Column(name = "expires_at", columnDefinition = "timestamptz")
  private Instant expiresAt;

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(Instant value) {
    this.expiresAt = value;
  }

  @Column(name = "saved_at", columnDefinition = "timestamptz")
  private Instant savedAt;

  public Instant getSavedAt() {
    return savedAt;
  }

  public void setSavedAt(Instant value) {
    this.savedAt = value;
  }

  @Transient private boolean fresh = true;

  @JsonIgnore
  @Override
  public boolean isNew() {
    return fresh;
  }

  @PostLoad
  @PostPersist
  public void markStored() {
    fresh = false;
  }
}
