package vn.edu.utc.rag.persistence.entity;

import java.io.Serializable;
import java.util.*;

public class ProfileProgramRecommendationsEntityId implements Serializable {
  public Long profileId;
  public Long programId;

  public ProfileProgramRecommendationsEntityId() {}

  @Override
  public boolean equals(Object o) {
    return o instanceof ProfileProgramRecommendationsEntityId other
        && Objects.equals(profileId, other.profileId)
        && Objects.equals(programId, other.programId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(profileId, programId);
  }
}
