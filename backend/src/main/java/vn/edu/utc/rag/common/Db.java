package vn.edu.utc.rag.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.hibernate.query.NativeQuery;
import org.postgresql.util.PGobject;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional
public class Db {
  private final EntityManager em;
  public final ObjectMapper json;

  public Db(EntityManager em, ObjectMapper json) {
    this.em = em;
    this.json = json;
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  public List<Map<String, Object>> list(String sql, Map<String, ?> args) {
    em.flush();
    NativeQuery query = em.createNativeQuery(sql).unwrap(NativeQuery.class);
    args.forEach(query::setParameter);
    query.setTupleTransformer(
        (tuple, aliases) -> {
          Map<String, Object> result = new LinkedHashMap<>();
          for (int i = 0; i < tuple.length; i++) {
            String name = aliases[i];
            Object value = tuple[i];
            if (value instanceof String text
                && Set.of(
                        "source",
                        "options",
                        "subjects",
                        "score_summary",
                        "scoring_config",
                        "score_weights",
                        "formula_config",
                        "details")
                    .contains(name)) {
              try {
                value = json.readValue(text, Object.class);
              } catch (Exception e) {
                throw new IllegalStateException("Invalid stored JSON", e);
              }
            } else if (value instanceof PGobject obj
                && (obj.getType().equals("jsonb") || obj.getType().equals("json"))) {
              try {
                value = json.readValue(obj.getValue(), Object.class);
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            } else if (value instanceof Timestamp t) value = t.toInstant().toString();
            else if (value instanceof OffsetDateTime t) value = t.toInstant().toString();
            else if (value instanceof Instant t) value = t.toString();
            else if (value instanceof UUID
                || value instanceof Long
                || value instanceof java.math.BigDecimal
                || value != null && (name.equals("id") || name.endsWith("_id")))
              value = value.toString();
            result.put(name, value);
          }
          return result;
        });
    return query.getResultList();
  }

  public Map<String, Object> one(String sql, Map<String, ?> args) {
    var rows = list(sql, args);
    if (rows.isEmpty()) throw ApiException.missing();
    return rows.getFirst();
  }

  public int update(String sql, Map<String, ?> args) {
    em.flush();
    var query = em.createNativeQuery(sql);
    args.forEach(query::setParameter);
    int count = query.executeUpdate();
    em
        .clear(); // Native mutations must not leave managed entities with stale auth/publication
                  // state.
    return count;
  }

  public Map<String, Object> decode(byte[] input) throws java.io.IOException {
    return json.readValue(
        input, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
  }

  public String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid JSON");
    }
  }

  public void audit(UUID actor, String action, String type, Object key, Object details) {
    var args = new HashMap<String, Object>();
    args.put("actor", actor);
    args.put("action", action);
    args.put("type", type);
    args.put("key", key.toString());
    args.put("details", encode(details));
    update(
        "INSERT INTO audit_events(actor_user_id,action,entity_type,entity_key,details,created_at)"
            + " VALUES(:actor,:action,:type,:key,CAST(:details AS jsonb),now())",
        args);
  }
}
