package vn.edu.utc.rag.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "role_assignments")
public class RoleAssignmentsEntity {
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

  @Column(name = "user_id", columnDefinition = "uuid")
  private UUID userId;

  public UUID getUserId() {
    return userId;
  }

  public void setUserId(UUID value) {
    this.userId = value;
  }

  @Column(name = "role_code", columnDefinition = "text")
  private String roleCode;

  public String getRoleCode() {
    return roleCode;
  }

  public void setRoleCode(String value) {
    this.roleCode = value;
  }

  @Column(name = "campus_id", columnDefinition = "smallint")
  private Short campusId;

  public Short getCampusId() {
    return campusId;
  }

  public void setCampusId(Short value) {
    this.campusId = value;
  }

  @Column(name = "assigned_at", columnDefinition = "timestamptz")
  private Instant assignedAt;

  public Instant getAssignedAt() {
    return assignedAt;
  }

  public void setAssignedAt(Instant value) {
    this.assignedAt = value;
  }
}
