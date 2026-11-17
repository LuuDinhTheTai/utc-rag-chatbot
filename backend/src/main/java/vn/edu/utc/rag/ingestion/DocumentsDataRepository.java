package vn.edu.utc.rag.ingestion;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional
public class DocumentsDataRepository {

  private final vn.edu.utc.rag.editorial.EditorialEntityWriter writer;

  public DocumentsDataRepository(
      vn.edu.utc.rag.editorial.EditorialEntityWriter writer,
      vn.edu.utc.rag.persistence.internal.EntityViews views,
      vn.edu.utc.rag.persistence.repository.ProgramsRepository programsJpa) {
    this.writer = writer;
    this.views = views;
    this.programsJpa = programsJpa;
  }

  public Map<String, Object> findProgramsForProgram(Map<String, ?> parameters) {
    var result = programsJpa.findById((Long) views.value(parameters, "id", Long.class));
    var rows = result.stream().map(e -> views.project(e, "*")).toList();
    if (rows.isEmpty()) throw vn.edu.utc.rag.common.ApiException.missing();
    return rows.getFirst();
  }

  private final vn.edu.utc.rag.persistence.internal.EntityViews views;

  private final vn.edu.utc.rag.persistence.repository.ProgramsRepository programsJpa;

  public Map<String, Object> createProgram(Map<String, Object> fields) {
    return writer.createPrograms(fields);
  }
}
