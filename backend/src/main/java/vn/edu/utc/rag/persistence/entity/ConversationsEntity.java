package vn.edu.utc.rag.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "conversations")
public class ConversationsEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", columnDefinition = "uuid")
  private UUID id;

  public UUID getId() {
    return id;
  }

  public void setId(UUID value) {
    this.id = value;
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

  @Column(name = "display_title", columnDefinition = "text")
  private String displayTitle;

  public String getDisplayTitle() {
    return displayTitle;
  }

  public void setDisplayTitle(String value) {
    this.displayTitle = value;
  }

  @Column(name = "expires_at", columnDefinition = "timestamptz")
  private Instant expiresAt;

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(Instant value) {
    this.expiresAt = value;
  }

  @Column(name = "created_at", columnDefinition = "timestamptz")
  private Instant createdAt;

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant value) {
    this.createdAt = value;
  }

  @Column(name = "updated_at", columnDefinition = "timestamptz")
  private Instant updatedAt;

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Instant value) {
    this.updatedAt = value;
  }
}
