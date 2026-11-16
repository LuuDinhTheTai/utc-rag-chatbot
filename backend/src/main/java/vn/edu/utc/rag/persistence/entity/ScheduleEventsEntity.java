package vn.edu.utc.rag.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "schedule_events")
public class ScheduleEventsEntity {
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

  @Column(name = "cycle_version_id", columnDefinition = "bigint")
  private Long cycleVersionId;

  public Long getCycleVersionId() {
    return cycleVersionId;
  }

  public void setCycleVersionId(Long value) {
    this.cycleVersionId = value;
  }

  @Column(name = "guide_version_id", columnDefinition = "bigint")
  private Long guideVersionId;

  public Long getGuideVersionId() {
    return guideVersionId;
  }

  public void setGuideVersionId(Long value) {
    this.guideVersionId = value;
  }

  @Column(name = "title", columnDefinition = "text")
  private String title;

  public String getTitle() {
    return title;
  }

  public void setTitle(String value) {
    this.title = value;
  }

  @Column(name = "starts_at", columnDefinition = "timestamptz")
  private Instant startsAt;

  public Instant getStartsAt() {
    return startsAt;
  }

  public void setStartsAt(Instant value) {
    this.startsAt = value;
  }

  @Column(name = "ends_at", columnDefinition = "timestamptz")
  private Instant endsAt;

  public Instant getEndsAt() {
    return endsAt;
  }

  public void setEndsAt(Instant value) {
    this.endsAt = value;
  }

  @Column(name = "location", columnDefinition = "text")
  private String location;

  public String getLocation() {
    return location;
  }

  public void setLocation(String value) {
    this.location = value;
  }

  @Column(name = "notes", columnDefinition = "text")
  private String notes;

  public String getNotes() {
    return notes;
  }

  public void setNotes(String value) {
    this.notes = value;
  }
}
