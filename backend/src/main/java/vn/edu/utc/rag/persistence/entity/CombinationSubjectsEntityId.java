package vn.edu.utc.rag.persistence.entity;

import java.io.Serializable;
import java.util.*;

public class CombinationSubjectsEntityId implements Serializable {
  public Short combinationId;
  public String subjectCode;

  public CombinationSubjectsEntityId() {}

  @Override
  public boolean equals(Object o) {
    return o instanceof CombinationSubjectsEntityId other
        && Objects.equals(combinationId, other.combinationId)
        && Objects.equals(subjectCode, other.subjectCode);
  }

  @Override
  public int hashCode() {
    return Objects.hash(combinationId, subjectCode);
  }
}
