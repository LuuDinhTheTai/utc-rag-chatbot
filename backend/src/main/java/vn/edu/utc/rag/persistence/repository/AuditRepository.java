package vn.edu.utc.rag.persistence.repository;

import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.utc.rag.persistence.entity.AuditEventsEntity;

@Repository
public class AuditRepository {

  private final AuditEventsRepository events;

  public AuditRepository(AuditEventsRepository events) {
    this.events = events;
  }

  @Transactional
  public void record(UUID actor, String action, String type, Object key, Object details) {
    var event = new AuditEventsEntity();
    event.setActorUserId(actor);
    event.setAction(action);
    event.setEntityType(type);
    event.setEntityKey(key.toString());
    event.setDetails((Map<String, Object>) details);
    event.setCreatedAt(Instant.now());
    events.saveAndFlush(event);
  }
}
