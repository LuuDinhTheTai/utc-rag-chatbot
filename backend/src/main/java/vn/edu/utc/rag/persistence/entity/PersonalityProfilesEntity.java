package vn.edu.utc.rag.persistence.entity;

import jakarta.persistence.*;
import java.util.*;

@Entity
@Table(name = "personality_profiles")
public class PersonalityProfilesEntity {
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

  @Column(name = "questionnaire_version_id", columnDefinition = "bigint")
  private Long questionnaireVersionId;

  public Long getQuestionnaireVersionId() {
    return questionnaireVersionId;
  }

  public void setQuestionnaireVersionId(Long value) {
    this.questionnaireVersionId = value;
  }

  @Column(name = "profile_code", columnDefinition = "text")
  private String profileCode;

  public String getProfileCode() {
    return profileCode;
  }

  public void setProfileCode(String value) {
    this.profileCode = value;
  }

  @Column(name = "name", columnDefinition = "text")
  private String name;

  public String getName() {
    return name;
  }

  public void setName(String value) {
    this.name = value;
  }

  @Column(name = "description", columnDefinition = "text")
  private String description;

  public String getDescription() {
    return description;
  }

  public void setDescription(String value) {
    this.description = value;
  }
}
