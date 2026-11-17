package vn.edu.utc.rag.service.ingestion;

import jakarta.servlet.http.HttpServletRequest;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.utc.rag.security.Permissions;
import vn.edu.utc.rag.validation.EditorialInput;
import vn.edu.utc.rag.validation.Input;

@Service()
@Transactional()
public class ProgramService {

  private final Permissions permissions;

  private final EditorialInput write;

  public ProgramService(
      Permissions permissions,
      EditorialInput write,
      vn.edu.utc.rag.service.audit.AuditService audit,
      vn.edu.utc.rag.mapper.editorial.EditorialMapper editorialMapper,
      vn.edu.utc.rag.mapper.common.EntityMapper views,
      vn.edu.utc.rag.persistence.repository.ProgramsRepository programsJpa) {
    this.permissions = permissions;
    this.write = write;
    this.audit = audit;
    this.editorialMapper = editorialMapper;
    this.views = views;
    this.programsJpa = programsJpa;
  }

  @Transactional
  public Map<String, Object> program(Map<String, Object> b, HttpServletRequest req) {
    var actor = permissions.check(req, null, "STAFF_EDITOR");
    Input.only(b, "field_id", "parent_program_id", "code", "name");
    var row = createProgram(write.fields(b, "field_id,code,name", "parent_program_id"));
    audit.record(actor, "CREATE", "programs", row.get("id"), Map.of());
    return row;
  }

  public Object program(String id, HttpServletRequest req) {
    permissions.check(req, null, "STAFF_EDITOR", "STAFF_REVIEWER");
    return findProgramsForProgram(Map.of("id", Input.id(id)));
  }

  private final vn.edu.utc.rag.service.audit.AuditService audit;

  private Map<String, Object> createProgram(Map<String, Object> fields) {
    return views.of(programsJpa.saveAndFlush(editorialMapper.toPrograms(fields)));
  }

  private final vn.edu.utc.rag.mapper.editorial.EditorialMapper editorialMapper;

  private Map<String, Object> findProgramsForProgram(Map<String, ?> parameters) {
    var result = programsJpa.findById((Long) views.value(parameters, "id", Long.class));
    var rows = result.stream().map(e -> views.project(e, "*")).toList();
    if (rows.isEmpty()) throw vn.edu.utc.rag.exception.ApiException.missing();
    return rows.getFirst();
  }

  private final vn.edu.utc.rag.mapper.common.EntityMapper views;

  private final vn.edu.utc.rag.persistence.repository.ProgramsRepository programsJpa;
}
