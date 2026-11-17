package vn.edu.utc.rag.persistence.query;

import java.util.*;

public record CampusScope(boolean global, Set<Short> ids) {
  public CampusScope {
    ids = Set.copyOf(ids);
  }

  public String predicate(String trustedColumn) {
    if (!Set.of("r.campus_id", "t.campus_id").contains(trustedColumn))
      throw new IllegalArgumentException("Unknown scope column");
    return global
        ? "true"
        : ids.isEmpty()
            ? "false"
            : trustedColumn
                + " IN ("
                + ids.stream()
                    .sorted()
                    .map(Object::toString)
                    .collect(java.util.stream.Collectors.joining(","))
                + ")";
  }
}
