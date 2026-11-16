package vn.edu.utc.rag.persistence.entity;

import java.io.Serializable;
import java.util.*;

public class PersonalityAnswersEntityId implements Serializable {
  public UUID attemptId;
  public Long questionId;

  public PersonalityAnswersEntityId() {}

  @Override
  public boolean equals(Object o) {
    return o instanceof PersonalityAnswersEntityId other
        && Objects.equals(attemptId, other.attemptId)
        && Objects.equals(questionId, other.questionId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(attemptId, questionId);
  }
}
