package vn.edu.utc.rag.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "audit_events")
public class AuditEventsEntity {
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

  @Column(name = "actor_user_id", columnDefinition = "uuid")
  private UUID actorUserId;

  public UUID getActorUserId() {
    return actorUserId;
  }

  public void setActorUserId(UUID value) {
    this.actorUserId = value;
  }

  @Column(name = "action", columnDefinition = "text")
  private String action;

  public String getAction() {
    return action;
  }

  public void setAction(String value) {
    this.action = value;
  }

  @Column(name = "entity_type", columnDefinition = "text")
  private String entityType;

  public String getEntityType() {
    return entityType;
  }

  public void setEntityType(String value) {
    this.entityType = value;
  }

  @Column(name = "entity_key", columnDefinition = "text")
  private String entityKey;

  public String getEntityKey() {
    return entityKey;
  }

  public void setEntityKey(String value) {
    this.entityKey = value;
  }

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "details", columnDefinition = "jsonb")
  private Map<String, Object> details;

  public Map<String, Object> getDetails() {
    return details;
  }

  public void setDetails(Map<String, Object> value) {
    this.details = value;
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
