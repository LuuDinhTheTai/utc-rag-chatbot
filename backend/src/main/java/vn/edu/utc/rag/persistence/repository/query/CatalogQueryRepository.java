package vn.edu.utc.rag.persistence.repository.query;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import vn.edu.utc.rag.validation.Input;

@Repository()
@Transactional()
public class CatalogQueryRepository {

  private final vn.edu.utc.rag.persistence.query.QueryPages pages;

  public CatalogQueryRepository(
      vn.edu.utc.rag.persistence.query.QueryPages pages,
      vn.edu.utc.rag.persistence.internal.NativeSqlExecutor query,
      vn.edu.utc.rag.mapper.common.EntityMapper views,
      vn.edu.utc.rag.persistence.repository.QuestionnaireVersionsRepository
          questionnaireVersionsJpa) {
    this.pages = pages;
    this.query = query;
    this.views = views;
    this.questionnaireVersionsJpa = questionnaireVersionsJpa;
  }

  private Map<String, Object> list(
      String sql,
      Map<String, Object> args,
      vn.edu.utc.rag.persistence.query.QueryPage req,
      Map<String, String> filters,
      String... order) {
    sql += " AND true" + vn.edu.utc.rag.persistence.query.QueryPages.filters(req, args, filters);
    String q = req.getParameter("q");
    if (q != null) {
      args.put("search", "%" + q + "%");
      sql += " AND CAST(row_to_json(t) AS text) ILIKE :search";
    }
    return pages.page(sql, args, req, order.length == 0 ? new String[] {"id:bigint:asc"} : order);
  }

  private Map<String, Object> one(String sql, String id) {
    return query.one(sql, Map.of("id", Input.id(id)));
  }

  public Object campuses(vn.edu.utc.rag.persistence.query.QueryPage req) {
    return list("SELECT * FROM campuses t WHERE true", new HashMap<>(), req, Map.of());
  }

  public Object suggestions(vn.edu.utc.rag.persistence.query.QueryPage req) {
    return list(
        "SELECT * FROM chat_suggestions t WHERE enabled",
        new HashMap<>(),
        req,
        Map.of("topic", "topic_code"),
        "display_order:smallint:asc",
        "id:bigint:asc");
  }

  public Object cycles(vn.edu.utc.rag.persistence.query.QueryPage req) {
    return list(
        "SELECT * FROM api_admission_cycles t WHERE true",
        new HashMap<>(),
        req,
        Map.of("campus_code", "campus_code", "year", "admission_year", "round_code", "round_code"));
  }

  public Object cycle(String id) {
    return one("SELECT * FROM api_admission_cycles WHERE id=:id", id);
  }

  private void cycleExists(String id) {
    one("SELECT id FROM api_admission_cycles WHERE id=:id", id);
  }

  public Object methods(String id, vn.edu.utc.rag.persistence.query.QueryPage req) {
    cycleExists(id);
    return list(
        "SELECT cm.id,m.code,m.name,cm.eligibility_text,coalesce(s.source,c.source) AS source FROM"
            + " cycle_methods cm JOIN api_admission_cycles c ON c.version_id=cm.cycle_version_id"
            + " JOIN admission_methods m ON m.id=cm.method_id LEFT JOIN api_verified_sources s ON"
            + " s.id=cm.source_revision_id CROSS JOIN LATERAL (SELECT m.code) t WHERE c.id=:id AND"
            + " (cm.source_revision_id IS NULL OR s.id IS NOT NULL)",
        new HashMap<>(Map.of("id", Input.id(id))),
        req,
        Map.of());
  }

  private static final String OPTIONS =
      "SELECT o.id,c.id AS cycle_id,c.campus_code,c.admission_year AS year,c.round_code,po.id AS"
          + " program_offering_id,p.code AS program_code,p.name AS program_name,m.code AS"
          + " method_code,ch.total_quota,o.method_quota,o.additional_conditions,coalesce(s.source,c.source)"
          + " AS source FROM admission_options o JOIN api_admission_cycles c ON"
          + " c.version_id=o.cycle_version_id JOIN admission_choices ch ON ch.id=o.choice_id JOIN"
          + " cycle_methods cm ON cm.id=o.cycle_method_id JOIN admission_methods m ON"
          + " m.id=cm.method_id JOIN program_offerings po ON po.id=ch.program_offering_id JOIN"
          + " programs p ON p.id=po.program_id LEFT JOIN api_verified_sources s ON"
          + " s.id=ch.source_revision_id LEFT JOIN api_verified_sources ms ON"
          + " ms.id=cm.source_revision_id WHERE (ch.source_revision_id IS NULL OR s.id IS NOT NULL)"
          + " AND (cm.source_revision_id IS NULL OR ms.id IS NOT NULL)";

  public Object options(String id, vn.edu.utc.rag.persistence.query.QueryPage req) {
    cycleExists(id);
    return list(
        "SELECT * FROM (" + OPTIONS + ") t WHERE cycle_id=:id",
        new HashMap<>(Map.of("id", Input.id(id))),
        req,
        Map.of("program_code", "program_code", "method_code", "method_code"));
  }

  public Object combinations(String id, vn.edu.utc.rag.persistence.query.QueryPage req) {
    one("SELECT id FROM (" + OPTIONS + ") t WHERE id=:id", id);
    return list(
        "SELECT t.id,t.code,oc.conditions_text,(SELECT"
            + " jsonb_agg(jsonb_build_object('subject_code',subject_code,'position',position) ORDER"
            + " BY position,subject_code) FROM combination_subjects WHERE combination_id=t.id) AS"
            + " subjects FROM subject_combinations t JOIN admission_option_combinations oc ON"
            + " oc.combination_id=t.id WHERE oc.option_id=:id",
        new HashMap<>(Map.of("id", Input.id(id))),
        req,
        Map.of());
  }

  public static final String CUTOFFS =
      "SELECT cutoff.id,o.campus_code,o.year,o.round_code,o.program_code,o.method_code,o.id AS"
          + " option_id,o.program_offering_id,comb.code AS"
          + " combination_code,cutoff.score,cutoff.scale_code,s.source FROM admission_cutoffs"
          + " cutoff JOIN ("
          + OPTIONS
          + ") o ON o.id=cutoff.option_id JOIN api_verified_sources s ON"
          + " s.id=cutoff.source_revision_id LEFT JOIN subject_combinations comb ON"
          + " comb.id=cutoff.combination_id";

  public Object cutoffs(vn.edu.utc.rag.persistence.query.QueryPage req) {
    return list(
        "SELECT * FROM (" + CUTOFFS + ") t WHERE true",
        new HashMap<>(),
        req,
        Map.of(
            "campus_code",
            "campus_code",
            "year",
            "year",
            "round_code",
            "round_code",
            "program_code",
            "program_code",
            "method_code",
            "method_code",
            "combination_code",
            "combination_code",
            "scale_code",
            "scale_code"));
  }

  public static final String PROGRAMS =
      "SELECT p.*,coalesce((SELECT cv.source FROM program_offerings po JOIN api_curricula cv"
          + " ON cv.program_offering_id=po.id WHERE po.program_id=p.id ORDER BY po.id LIMIT 1),"
          + " (SELECT o.source FROM ("
          + OPTIONS
          + ") o JOIN program_offerings po ON po.id=o.program_offering_id WHERE po.program_id=p.id"
          + " ORDER BY po.id LIMIT 1)) AS source FROM programs p WHERE EXISTS (SELECT 1 FROM"
          + " program_offerings po JOIN api_curricula cv ON cv.program_offering_id=po.id WHERE"
          + " po.program_id=p.id) OR EXISTS (SELECT 1 FROM ("
          + OPTIONS
          + ") o JOIN program_offerings po ON po.id=o.program_offering_id WHERE"
          + " po.program_id=p.id)";

  public Object programs(vn.edu.utc.rag.persistence.query.QueryPage req) {
    var args = new HashMap<String, Object>();
    String sql =
        "SELECT t.* FROM (" + PROGRAMS + ") t JOIN academic_fields f ON f.id=t.field_id WHERE true";
    String campus = req.getParameter("campus_code"), year = req.getParameter("year");
    if (campus != null || year != null) {
      sql +=
          " AND EXISTS (SELECT 1 FROM program_offerings po JOIN campuses ca ON ca.id=po.campus_id"
              + " WHERE po.program_id=t.id";
      if (campus != null) {
        sql += " AND ca.code=:campus";
        args.put("campus", campus);
      }
      if (year != null) {
        sql += " AND CAST(po.entry_year AS text)=:year";
        args.put("year", year);
      }
      sql +=
          " AND (EXISTS (SELECT 1 FROM api_curricula cv WHERE cv.program_offering_id=po.id)"
              + " OR EXISTS (SELECT 1 FROM ("
              + OPTIONS
              + ") o WHERE o.program_offering_id=po.id)))";
    }
    return list(sql, args, req, Map.of("field_code", "f.code"));
  }

  public Object program(String id) {
    return one("SELECT * FROM (" + PROGRAMS + ") p WHERE id=:id", id);
  }

  public static final String OFFERINGS =
      "SELECT po.*,p.code AS program_code,p.name AS program_name,ca.code AS campus_code,cv.source"
          + " FROM program_offerings po JOIN programs p ON p.id=po.program_id JOIN campuses ca ON"
          + " ca.id=po.campus_id JOIN api_curricula cv ON cv.program_offering_id=po.id";

  public Object offerings(vn.edu.utc.rag.persistence.query.QueryPage req) {
    return list(
        "SELECT * FROM (" + OFFERINGS + ") t WHERE true",
        new HashMap<>(),
        req,
        Map.of("campus_code", "campus_code", "year", "entry_year", "program_id", "program_id"));
  }

  public Object curriculum(String id) {
    return one(
        "SELECT"
            + " id,program_offering_id,version_no,duration_months,total_credits,objectives,learning_outcomes,career_paths,source,updated_at"
            + " FROM api_curricula WHERE program_offering_id=:id",
        id);
  }

  public Object courses(String id, vn.edu.utc.rag.persistence.query.QueryPage req) {
    var cv = one("SELECT id FROM api_curricula WHERE program_offering_id=:id", id);
    var args = new HashMap<String, Object>(Map.of("v", Input.id(cv.get("id"))));
    return list(
        "SELECT c.id,c.code,t.course_title,t.credits,t.course_group,t.suggested_term,cv.source FROM"
            + " curriculum_courses t JOIN courses c ON c.id=t.course_id JOIN api_curricula cv ON"
            + " cv.id=t.curriculum_version_id WHERE t.curriculum_version_id=:v",
        args,
        req,
        Map.of(
            "curriculum_id",
            "t.curriculum_version_id",
            "group",
            "t.course_group",
            "term",
            "t.suggested_term"));
  }

  public Object questionnaires(vn.edu.utc.rag.persistence.query.QueryPage req) {
    return list(
        "SELECT t.*,v.id AS version_id,v.instructions FROM questionnaires t JOIN"
            + " questionnaire_versions v ON v.questionnaire_id=t.id AND v.status='PUBLISHED' WHERE"
            + " true",
        new HashMap<>(),
        req,
        Map.of());
  }

  public Object questions(
      String id, String version, vn.edu.utc.rag.persistence.query.QueryPage req) {
    findQuestionnaireVersionsForQuestions(Map.of("v", Input.id(version), "q", Input.id(id)));
    return questionsForVersion(Input.id(version), req);
  }

  public Object questionsForVersion(long version, vn.edu.utc.rag.persistence.query.QueryPage req) {
    return list(
        "SELECT t.id,t.position,t.body,(SELECT jsonb_agg(jsonb_build_object('id',CAST(o.id AS"
            + " text),'position',o.position,'label',o.label) ORDER BY o.position,o.id) FROM"
            + " personality_options o WHERE o.question_id=t.id) AS options FROM"
            + " personality_questions t WHERE t.questionnaire_version_id=:v",
        new HashMap<>(Map.of("v", version)),
        req,
        Map.of(),
        "position:smallint:asc",
        "id:bigint:asc");
  }

  public Object guides(vn.edu.utc.rag.persistence.query.QueryPage req) {
    return list(
        "SELECT * FROM api_guides t WHERE true",
        new HashMap<>(),
        req,
        Map.of(
            "campus_code",
            "campus_code",
            "year",
            "admission_year",
            "type",
            "guide_type",
            "audience_code",
            "audience_code",
            "method_id",
            "method_id",
            "cycle_id",
            "cycle_id"));
  }

  public Object guide(String id) {
    return one("SELECT * FROM api_guides WHERE id=:id", id);
  }

  public Object entries(String id, vn.edu.utc.rag.persistence.query.QueryPage req) {
    var g = one("SELECT version_id FROM api_guides WHERE id=:id", id);
    return list(
        "SELECT * FROM guide_entries t WHERE guide_version_id=:v",
        new HashMap<>(Map.of("v", Input.id(g.get("version_id")))),
        req,
        Map.of(),
        "position:smallint:asc",
        "id:bigint:asc");
  }

  public Object schedule(vn.edu.utc.rag.persistence.query.QueryPage req) {
    return list(
        "SELECT t.*,coalesce(c.campus_code,g.campus_code) AS"
            + " campus_code,coalesce(c.admission_year,g.admission_year) AS"
            + " year,coalesce(c.source,g.source) AS source FROM schedule_events t LEFT JOIN"
            + " api_admission_cycles c ON c.version_id=t.cycle_version_id LEFT JOIN api_guides g ON"
            + " g.version_id=t.guide_version_id WHERE (c.id IS NOT NULL OR g.id IS NOT NULL)",
        new HashMap<>(),
        req,
        Map.of(
            "campus_code",
            "coalesce(c.campus_code,g.campus_code)",
            "year",
            "coalesce(c.admission_year,g.admission_year)",
            "cycle_id",
            "c.id",
            "guide_id",
            "g.id"),
        "starts_at:timestamptz:asc",
        "id:bigint:asc");
  }

  public Object finances(vn.edu.utc.rag.persistence.query.QueryPage req) {
    return list(
        "SELECT * FROM api_finances t WHERE true",
        new HashMap<>(),
        req,
        Map.of(
            "campus_code",
            "campus_code",
            "year",
            "entry_year",
            "type",
            "policy_type",
            "program_offering_id",
            "program_offering_id"));
  }

  public Object finance(String id) {
    return one("SELECT * FROM api_finances WHERE id=:id", id);
  }

  private final vn.edu.utc.rag.persistence.internal.NativeSqlExecutor query;

  private Map<String, Object> findQuestionnaireVersionsForQuestions(Map<String, ?> parameters) {
    var result =
        questionnaireVersionsJpa.findPublishedByIdAndQuestionnaireId(
            (Long) views.value(parameters, "v", Long.class),
            (Long) views.value(parameters, "q", Long.class));
    var rows = result.stream().map(e -> views.project(e, "id")).toList();
    if (rows.isEmpty()) throw vn.edu.utc.rag.exception.ApiException.missing();
    return rows.getFirst();
  }

  private final vn.edu.utc.rag.mapper.common.EntityMapper views;

  private final vn.edu.utc.rag.persistence.repository.QuestionnaireVersionsRepository
      questionnaireVersionsJpa;
}
