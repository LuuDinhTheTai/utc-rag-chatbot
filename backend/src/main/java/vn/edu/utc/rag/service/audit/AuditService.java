package vn.edu.utc.rag.service.audit;

import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.utc.rag.persistence.entity.AuditEventsEntity;
import vn.edu.utc.rag.persistence.repository.AuditEventsRepository;

@Service
public class AuditService {

  private final AuditEventsRepository events;

  public AuditService(AuditEventsRepository events) {
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
