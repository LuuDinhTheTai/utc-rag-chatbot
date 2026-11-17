package vn.edu.utc.rag.persistence.entity;

import jakarta.persistence.*;
import java.util.*;

@Entity
@Table(name = "combination_subjects")
@IdClass(CombinationSubjectsEntityId.class)
public class CombinationSubjectsEntity {

  @Id
  @Column(name = "combination_id", columnDefinition = "smallint")
  private Short combinationId;

  public Short getCombinationId() {
    return combinationId;
  }

  public void setCombinationId(Short value) {
    this.combinationId = value;
  }

  @Id
  @Column(name = "subject_code", columnDefinition = "text")
  private String subjectCode;

  public String getSubjectCode() {
    return subjectCode;
  }

  public void setSubjectCode(String value) {
    this.subjectCode = value;
  }

  @Column(name = "position", columnDefinition = "smallint")
  private Short position;

  public Short getPosition() {
    return position;
  }

  public void setPosition(Short value) {
    this.position = value;
  }
}
