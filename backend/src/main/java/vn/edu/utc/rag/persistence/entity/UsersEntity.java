package vn.edu.utc.rag.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "users")
public class UsersEntity {
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

  @Column(name = "email", columnDefinition = "text")
  private String email;

  public String getEmail() {
    return email;
  }

  public void setEmail(String value) {
    this.email = value;
  }

  @Column(name = "password_hash", columnDefinition = "text")
  private String passwordHash;

  public String getPasswordHash() {
    return passwordHash;
  }

  public void setPasswordHash(String value) {
    this.passwordHash = value;
  }

  @Column(name = "status", columnDefinition = "text")
  private String status;

  public String getStatus() {
    return status;
  }

  public void setStatus(String value) {
    this.status = value;
  }

  @Column(name = "auth_epoch", columnDefinition = "integer")
  private Integer authEpoch;

  public Integer getAuthEpoch() {
    return authEpoch;
  }

  public void setAuthEpoch(Integer value) {
    this.authEpoch = value;
  }

  @Column(name = "created_at", columnDefinition = "timestamptz")
  private Instant createdAt;

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant value) {
    this.createdAt = value;
  }
}
