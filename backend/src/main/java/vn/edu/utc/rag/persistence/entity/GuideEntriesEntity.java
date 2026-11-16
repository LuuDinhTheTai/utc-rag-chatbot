package vn.edu.utc.rag.persistence.entity;

import jakarta.persistence.*;
import java.util.*;

@Entity
@Table(name = "guide_entries")
public class GuideEntriesEntity {
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

  @Column(name = "guide_version_id", columnDefinition = "bigint")
  private Long guideVersionId;

  public Long getGuideVersionId() {
    return guideVersionId;
  }

  public void setGuideVersionId(Long value) {
    this.guideVersionId = value;
  }

  @Column(name = "position", columnDefinition = "smallint")
  private Short position;

  public Short getPosition() {
    return position;
  }

  public void setPosition(Short value) {
    this.position = value;
  }

  @Column(name = "entry_type", columnDefinition = "text")
  private String entryType;

  public String getEntryType() {
    return entryType;
  }

  public void setEntryType(String value) {
    this.entryType = value;
  }

  @Column(name = "label", columnDefinition = "text")
  private String label;

  public String getLabel() {
    return label;
  }

  public void setLabel(String value) {
    this.label = value;
  }

  @Column(name = "instructions", columnDefinition = "text")
  private String instructions;

  public String getInstructions() {
    return instructions;
  }

  public void setInstructions(String value) {
    this.instructions = value;
  }

  @Column(name = "link_url", columnDefinition = "text")
  private String linkUrl;

  public String getLinkUrl() {
    return linkUrl;
  }

  public void setLinkUrl(String value) {
    this.linkUrl = value;
  }

  @Column(name = "is_required", columnDefinition = "boolean")
  private Boolean isRequired;

  public Boolean getIsRequired() {
    return isRequired;
  }

  public void setIsRequired(Boolean value) {
    this.isRequired = value;
  }
}
