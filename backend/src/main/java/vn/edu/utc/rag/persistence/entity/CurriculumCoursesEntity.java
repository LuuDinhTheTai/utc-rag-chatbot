package vn.edu.utc.rag.persistence.entity;

import jakarta.persistence.*;
import java.util.*;

@Entity
@Table(name = "curriculum_courses")
@IdClass(CurriculumCoursesEntityId.class)
public class CurriculumCoursesEntity {
  @Id
  @Column(name = "curriculum_version_id", columnDefinition = "bigint")
  private Long curriculumVersionId;

  public Long getCurriculumVersionId() {
    return curriculumVersionId;
  }

  public void setCurriculumVersionId(Long value) {
    this.curriculumVersionId = value;
  }

  @Id
  @Column(name = "course_id", columnDefinition = "bigint")
  private Long courseId;

  public Long getCourseId() {
    return courseId;
  }

  public void setCourseId(Long value) {
    this.courseId = value;
  }

  @Column(name = "course_title", columnDefinition = "text")
  private String courseTitle;

  public String getCourseTitle() {
    return courseTitle;
  }

  public void setCourseTitle(String value) {
    this.courseTitle = value;
  }

  @Column(name = "credits", columnDefinition = "smallint")
  private Short credits;

  public Short getCredits() {
    return credits;
  }

  public void setCredits(Short value) {
    this.credits = value;
  }

  @Column(name = "course_group", columnDefinition = "text")
  private String courseGroup;

  public String getCourseGroup() {
    return courseGroup;
  }

  public void setCourseGroup(String value) {
    this.courseGroup = value;
  }

  @Column(name = "suggested_term", columnDefinition = "smallint")
  private Short suggestedTerm;

  public Short getSuggestedTerm() {
    return suggestedTerm;
  }

  public void setSuggestedTerm(Short value) {
    this.suggestedTerm = value;
  }
}
