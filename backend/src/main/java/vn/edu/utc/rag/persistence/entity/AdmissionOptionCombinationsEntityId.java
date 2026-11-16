package vn.edu.utc.rag.persistence.entity;

import java.io.Serializable;
import java.util.*;

public class AdmissionOptionCombinationsEntityId implements Serializable {
  public Long optionId;
  public Short combinationId;

  public AdmissionOptionCombinationsEntityId() {}

  @Override
  public boolean equals(Object o) {
    return o instanceof AdmissionOptionCombinationsEntityId other
        && Objects.equals(optionId, other.optionId)
        && Objects.equals(combinationId, other.combinationId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(optionId, combinationId);
  }
}
