package vn.edu.utc.rag.persistence.entity;

import java.io.Serializable;
import java.util.*;

public class MessageCitationsEntityId implements Serializable {

  public UUID messageId;

  public Short position;

  public MessageCitationsEntityId() {}

  @Override
  public boolean equals(Object o) {
    return o instanceof MessageCitationsEntityId other
        && Objects.equals(messageId, other.messageId)
        && Objects.equals(position, other.position);
  }

  @Override
  public int hashCode() {
    return Objects.hash(messageId, position);
  }
}
