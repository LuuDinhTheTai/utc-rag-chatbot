package vn.edu.utc.rag.persistence.entity;

import jakarta.persistence.*;
import java.util.*;

@Entity
@Table(name = "guide_items")
public class GuideItemsEntity {
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

  @Column(name = "cycle_id", columnDefinition = "bigint")
  private Long cycleId;

  public Long getCycleId() {
    return cycleId;
  }

  public void setCycleId(Long value) {
    this.cycleId = value;
  }

  @Column(name = "method_id", columnDefinition = "smallint")
  private Short methodId;

  public Short getMethodId() {
    return methodId;
  }

  public void setMethodId(Short value) {
    this.methodId = value;
  }

  @Column(name = "guide_type", columnDefinition = "text")
  private String guideType;

  public String getGuideType() {
    return guideType;
  }

  public void setGuideType(String value) {
    this.guideType = value;
  }

  @Column(name = "audience_code", columnDefinition = "text")
  private String audienceCode;

  public String getAudienceCode() {
    return audienceCode;
  }

  public void setAudienceCode(String value) {
    this.audienceCode = value;
  }

  @Column(name = "guide_slug", columnDefinition = "text")
  private String guideSlug;

  public String getGuideSlug() {
    return guideSlug;
  }

  public void setGuideSlug(String value) {
    this.guideSlug = value;
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
