package vn.edu.utc.rag.persistence.entity;

import java.io.Serializable;
import java.util.*;

public class CurriculumCoursesEntityId implements Serializable {
  public Long curriculumVersionId;
  public Long courseId;

  public CurriculumCoursesEntityId() {}

  @Override
  public boolean equals(Object o) {
    return o instanceof CurriculumCoursesEntityId other
        && Objects.equals(curriculumVersionId, other.curriculumVersionId)
        && Objects.equals(courseId, other.courseId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(curriculumVersionId, courseId);
  }
}
