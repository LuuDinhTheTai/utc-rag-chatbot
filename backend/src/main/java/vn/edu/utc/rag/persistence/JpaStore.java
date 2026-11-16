package vn.edu.utc.rag.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.support.Repositories;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Routes metadata-driven editorial creation to the mapped Spring Data repository. */
@Component
public class JpaStore {
  private final EntityManager em;
  private final Repositories repositories;
  private final ObjectMapper json;

  public JpaStore(EntityManager em, ListableBeanFactory beans, ObjectMapper json) {
    this.em = em;
    this.repositories = new Repositories(beans);
    this.json = json;
  }

  @Transactional
  @SuppressWarnings({"unchecked", "rawtypes"})
  public Object insert(String table, Map<String, Object> fields) {
    try {
      Class<?> type =
          em.getMetamodel().getEntities().stream()
              .filter(
                  e ->
                      e.getJavaType()
                          .getAnnotation(jakarta.persistence.Table.class)
                          .name()
                          .equals(table))
              .findFirst()
              .orElseThrow()
              .getJavaType();
      Object entity = type.getConstructor().newInstance();
      for (var field : type.getDeclaredFields()) {
        var column = field.getAnnotation(jakarta.persistence.Column.class);
        if (column == null || !fields.containsKey(column.name())) continue;
        Object value = fields.get(column.name());
        if (value instanceof Number n && field.getType() == Short.class) value = n.shortValue();
        if (value instanceof OffsetDateTime t && field.getType() == Instant.class)
          value = t.toInstant();
        if (value instanceof String s && Map.class.isAssignableFrom(field.getType()))
          value = json.readValue(s, Map.class);
        field.setAccessible(true);
        field.set(entity, value);
      }
      ((JpaRepository) repositories.getRepositoryFor(type).orElseThrow()).saveAndFlush(entity);
      return em.getEntityManagerFactory().getPersistenceUnitUtil().getIdentifier(entity);
    } catch (ReflectiveOperationException | java.io.IOException e) {
      throw new IllegalStateException("Invalid entity mapping", e);
    }
  }
}
