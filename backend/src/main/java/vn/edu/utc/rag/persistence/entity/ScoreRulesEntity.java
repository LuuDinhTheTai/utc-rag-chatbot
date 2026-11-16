package vn.edu.utc.rag.persistence.entity;

import jakarta.persistence.*;
import java.util.*;

@Entity
@Table(name = "score_rules")
public class ScoreRulesEntity {
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

  @Column(name = "method_id", columnDefinition = "smallint")
  private Short methodId;

  public Short getMethodId() {
    return methodId;
  }

  public void setMethodId(Short value) {
    this.methodId = value;
  }

  @Column(name = "publication_version", columnDefinition = "bigint")
  private Long publicationVersion = 0L;

  public Long getPublicationVersion() {
    return publicationVersion;
  }

  public void setPublicationVersion(Long value) {
    this.publicationVersion = value;
  }
}
