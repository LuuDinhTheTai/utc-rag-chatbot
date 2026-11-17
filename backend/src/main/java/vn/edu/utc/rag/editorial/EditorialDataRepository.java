package vn.edu.utc.rag.editorial;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional
public class EditorialDataRepository {

  private final vn.edu.utc.rag.editorial.EditorialEntityWriter writer;

  public EditorialDataRepository(
      vn.edu.utc.rag.editorial.EditorialEntityWriter writer,
      vn.edu.utc.rag.persistence.internal.EntityViews views,
      vn.edu.utc.rag.persistence.repository.AdmissionChoicesRepository admissionChoicesJpa,
      vn.edu.utc.rag.persistence.repository.AdmissionCutoffsRepository admissionCutoffsJpa,
      vn.edu.utc.rag.persistence.repository.AdmissionOptionCombinationsRepository
          admissionOptionCombinationsJpa,
      vn.edu.utc.rag.persistence.repository.AdmissionOptionsRepository admissionOptionsJpa,
      vn.edu.utc.rag.persistence.repository.CampusesRepository campusesJpa,
      vn.edu.utc.rag.persistence.repository.CurriculumCoursesRepository curriculumCoursesJpa,
      vn.edu.utc.rag.persistence.repository.CycleMethodsRepository cycleMethodsJpa,
      vn.edu.utc.rag.persistence.repository.DocumentRevisionsRepository documentRevisionsJpa,
      vn.edu.utc.rag.persistence.repository.GuideEntriesRepository guideEntriesJpa,
      vn.edu.utc.rag.persistence.repository.ProgramOfferingsRepository programOfferingsJpa,
      vn.edu.utc.rag.persistence.repository.ScoreRuleBandsRepository scoreRuleBandsJpa,
      vn.edu.utc.rag.persistence.repository.ScoreRuleInputsRepository scoreRuleInputsJpa,
      vn.edu.utc.rag.persistence.query.QueryPages queryPages,
      EditorialVersionsRepository versions,
      vn.edu.utc.rag.persistence.repository.ScheduleEventsRepository scheduleEventsJpa) {
    this.writer = writer;
    this.views = views;
    this.admissionChoicesJpa = admissionChoicesJpa;
    this.admissionCutoffsJpa = admissionCutoffsJpa;
    this.admissionOptionCombinationsJpa = admissionOptionCombinationsJpa;
    this.admissionOptionsJpa = admissionOptionsJpa;
    this.campusesJpa = campusesJpa;
    this.curriculumCoursesJpa = curriculumCoursesJpa;
    this.cycleMethodsJpa = cycleMethodsJpa;
    this.documentRevisionsJpa = documentRevisionsJpa;
    this.guideEntriesJpa = guideEntriesJpa;
    this.programOfferingsJpa = programOfferingsJpa;
    this.scoreRuleBandsJpa = scoreRuleBandsJpa;
    this.scoreRuleInputsJpa = scoreRuleInputsJpa;
    this.queryPages = queryPages;
    this.versions = versions;
    this.scheduleEventsJpa = scheduleEventsJpa;
  }

  public Map<String, Object> findRecordsForItem(
      Map<String, ?> parameters, vn.edu.utc.rag.editorial.EditorialKind k, boolean lock) {
    return versions.item(k, ((Number) parameters.get("id")).longValue(), lock);
  }

  public Map<String, Object> findCampusesForCreate(Map<String, ?> parameters) {
    var result = campusesJpa.findAllByCode((String) views.value(parameters, "code", String.class));
    var rows = result.stream().map(e -> views.project(e, "id")).toList();
    if (rows.isEmpty()) throw vn.edu.utc.rag.common.ApiException.missing();
    return rows.getFirst();
  }

  public Map<String, Object> findRecordsForCreate(Map<String, ?> parameters, String table) {
    var kind =
        table.equals("admission_cycles")
            ? EditorialKind.ALL.get("admission-cycles")
            : EditorialKind.ALL.get("program-offerings");
    var row = versions.item(kind, ((Number) parameters.get("id")).longValue(), false);
    return Map.of(
        "campus_id",
        row.get("campus_id"),
        "year",
        row.get(table.equals("admission_cycles") ? "admission_year" : "entry_year"));
  }

  public Map<String, Object> findRecordsForVersion(
      Map<String, ?> parameters, vn.edu.utc.rag.editorial.EditorialKind k) {
    return versions.version(
        k,
        ((Number) parameters.get("v")).longValue(),
        ((Number) parameters.get("id")).longValue(),
        false);
  }

  public List<Map<String, Object>> findCycleMethodsForChildren(Map<String, ?> parameters) {
    var result =
        cycleMethodsJpa.findAllByCycleVersionIdOrderByIdAsc(
            (Long) views.value(parameters, "v", Long.class));
    var rows = result.stream().map(e -> views.project(e, "*")).toList();
    return rows;
  }

  public List<Map<String, Object>> findAdmissionChoicesForChildren(Map<String, ?> parameters) {
    var result =
        admissionChoicesJpa.findAllByCycleVersionIdOrderByIdAsc(
            (Long) views.value(parameters, "v", Long.class));
    var rows = result.stream().map(e -> views.project(e, "*")).toList();
    return rows;
  }

  public List<Map<String, Object>> findAdmissionOptionsForChildren(Map<String, ?> parameters) {
    return admissionOptionsJpa
        .findOptionsWithMethod(((Number) parameters.get("id")).longValue())
        .stream()
        .map(
            v -> {
              var row = views.of(v.option());
              row.put("method_id", v.methodId().toString());
              return row;
            })
        .toList();
  }

  public List<Map<String, Object>> findAdmissionOptionCombinationsForChildren(
      Map<String, ?> parameters) {
    var result =
        admissionOptionCombinationsJpa.findAllByOptionIdOrderByCombinationIdAsc(
            (Long) views.value(parameters, "id", Long.class));
    var rows = result.stream().map(e -> views.project(e, "*")).toList();
    return rows;
  }

  public List<Map<String, Object>> findAdmissionCutoffsForChildren(Map<String, ?> parameters) {
    var result =
        admissionCutoffsJpa.findAllByOptionIdOrderByIdAsc(
            (Long) views.value(parameters, "id", Long.class));
    var rows = result.stream().map(e -> views.project(e, "*")).toList();
    return rows;
  }

  public List<Map<String, Object>> findCurriculumCoursesForChildren(Map<String, ?> parameters) {
    var result =
        curriculumCoursesJpa.findAllByCurriculumVersionIdOrderByCourseIdAsc(
            (Long) views.value(parameters, "v", Long.class));
    var rows = result.stream().map(e -> views.project(e, "*")).toList();
    return rows;
  }

  public List<Map<String, Object>> findGuideEntriesForChildren(Map<String, ?> parameters) {
    var result =
        guideEntriesJpa.findAllByGuideVersionIdOrderByPositionAscIdAsc(
            (Long) views.value(parameters, "v", Long.class));
    var rows = result.stream().map(e -> views.project(e, "*")).toList();
    return rows;
  }

  public List<Map<String, Object>> findScoreRuleInputsForChildren(Map<String, ?> parameters) {
    var result =
        scoreRuleInputsJpa.findAllByRuleVersionIdOrderByIdAsc(
            (Long) views.value(parameters, "v", Long.class));
    var rows = result.stream().map(e -> views.project(e, "*")).toList();
    return rows;
  }

  public List<Map<String, Object>> findScoreRuleBandsForChildren(Map<String, ?> parameters) {
    var result =
        scoreRuleBandsJpa.findAllByRuleInputIdOrderByIdAsc(
            (Long) views.value(parameters, "id", Long.class));
    var rows = result.stream().map(e -> views.project(e, "*")).toList();
    return rows;
  }

  public List<Map<String, Object>> findRecordsForChildren(
      Map<String, ?> parameters, vn.edu.utc.rag.editorial.EditorialKind k) {
    Long id = ((Number) parameters.get("v")).longValue();
    return (k.table().equals("admission_cycles")
            ? scheduleEventsJpa.findAllByCycleVersionIdOrderByStartsAtAscIdAsc(id)
            : scheduleEventsJpa.findAllByGuideVersionIdOrderByStartsAtAscIdAsc(id))
        .stream().map(views::of).toList();
  }

  public Map<String, Object> findDocumentRevisionsForCheckSource(Map<String, ?> parameters) {
    var result =
        documentRevisionsJpa.findLockedById((Long) views.value(parameters, "id", Long.class));
    var rows = result.stream().map(e -> views.project(e, "campus_id,revoked_at")).toList();
    if (rows.isEmpty()) throw vn.edu.utc.rag.common.ApiException.missing();
    return rows.getFirst();
  }

  public Map<String, Object> findRecordsForSave(
      Map<String, ?> parameters, vn.edu.utc.rag.editorial.EditorialKind k) {
    return Map.of("n", versions.nextVersion(k, ((Number) parameters.get("id")).longValue()));
  }

  public Map<String, Object> findRecordsForSave2(
      Map<String, ?> parameters, vn.edu.utc.rag.editorial.EditorialKind k) {
    return versions.version(
        k,
        ((Number) parameters.get("v")).longValue(),
        ((Number) parameters.get("id")).longValue(),
        true);
  }

  public int applyRecordsForSave(
      Map<String, ?> parameters, vn.edu.utc.rag.editorial.EditorialKind k) {
    versions.updateDraft(k, ((Number) parameters.get("v")).longValue(), parameters);
    return 1;
  }

  public int applyAdmissionCutoffsForDeleteChildren(Map<String, ?> parameters) {
    return admissionCutoffsJpa.deleteByCycleVersion(((Number) parameters.get("v")).longValue());
  }

  public int applyAdmissionOptionCombinationsForDeleteChildren(Map<String, ?> parameters) {
    return admissionOptionCombinationsJpa.deleteByCycleVersion(
        ((Number) parameters.get("v")).longValue());
  }

  public int applyAdmissionOptionsForDeleteChildren(Map<String, ?> parameters) {
    {
      int count =
          admissionOptionsJpa.deleteByCycleVersionId(
              (Long) views.value(parameters, "v", Long.class));
      admissionOptionsJpa.flush();
      return count;
    }
  }

  public int applyAdmissionChoicesForDeleteChildren(Map<String, ?> parameters) {
    {
      int count =
          admissionChoicesJpa.deleteByCycleVersionId(
              (Long) views.value(parameters, "v", Long.class));
      admissionChoicesJpa.flush();
      return count;
    }
  }

  public int applyCycleMethodsForDeleteChildren(Map<String, ?> parameters) {
    {
      int count =
          cycleMethodsJpa.deleteByCycleVersionId((Long) views.value(parameters, "v", Long.class));
      cycleMethodsJpa.flush();
      return count;
    }
  }

  public int applyCurriculumCoursesForDeleteChildren(Map<String, ?> parameters) {
    {
      int count =
          curriculumCoursesJpa.deleteByCurriculumVersionId(
              (Long) views.value(parameters, "v", Long.class));
      curriculumCoursesJpa.flush();
      return count;
    }
  }

  public int applyGuideEntriesForDeleteChildren(Map<String, ?> parameters) {
    {
      int count =
          guideEntriesJpa.deleteByGuideVersionId((Long) views.value(parameters, "v", Long.class));
      guideEntriesJpa.flush();
      return count;
    }
  }

  public int applyScoreRuleBandsForDeleteChildren(Map<String, ?> parameters) {
    return scoreRuleBandsJpa.deleteByRuleVersion(((Number) parameters.get("v")).longValue());
  }

  public int applyScoreRuleInputsForDeleteChildren(Map<String, ?> parameters) {
    {
      int count =
          scoreRuleInputsJpa.deleteByRuleVersionId((Long) views.value(parameters, "v", Long.class));
      scoreRuleInputsJpa.flush();
      return count;
    }
  }

  public int applyRecordsForDeleteChildren(
      Map<String, ?> parameters, vn.edu.utc.rag.editorial.EditorialKind k) {
    Long id = ((Number) parameters.get("v")).longValue();
    if (k.table().equals("admission_cycles")) scheduleEventsJpa.deleteByCycleVersionId(id);
    else scheduleEventsJpa.deleteByGuideVersionId(id);
    return 1;
  }

  public Map<String, Object> findProgramOfferingsForPutChildren(Map<String, ?> parameters) {
    var result = programOfferingsJpa.findById((Long) views.value(parameters, "id", Long.class));
    var rows = result.stream().map(e -> views.project(e, "campus_id")).toList();
    if (rows.isEmpty()) throw vn.edu.utc.rag.common.ApiException.missing();
    return rows.getFirst();
  }

  public List<Map<String, Object>> findRecordsForPublication(
      Map<String, ?> parameters, vn.edu.utc.rag.editorial.EditorialKind k) {
    return versions.publication(k, ((Number) parameters.get("id")).longValue());
  }

  public Map<String, Object> findRecordsForPublish(
      Map<String, ?> parameters, vn.edu.utc.rag.editorial.EditorialKind k) {
    return versions.version(
        k,
        ((Number) parameters.get("v")).longValue(),
        ((Number) parameters.get("id")).longValue(),
        true);
  }

  public Map<String, Object> findApiVerifiedSourcesForPublish(Map<String, ?> parameters) {
    return verifiedSource(((Number) parameters.get("id")).longValue());
  }

  public int applyRecordsForPublish(
      Map<String, ?> parameters, vn.edu.utc.rag.editorial.EditorialKind k) {
    versions.archivePublished(k, ((Number) parameters.get("id")).longValue());
    return 1;
  }

  public int applyRecordsForPublish2(
      Map<String, ?> parameters, vn.edu.utc.rag.editorial.EditorialKind k) {
    versions.publish(k, ((Number) parameters.get("id")).longValue(), (UUID) parameters.get("u"));
    return 1;
  }

  public int applyRecordsForPublish3(
      Map<String, ?> parameters, vn.edu.utc.rag.editorial.EditorialKind k) {
    versions.incrementPublication(k, ((Number) parameters.get("id")).longValue());
    return 1;
  }

  public List<Map<String, Object>> findCycleMethodsForVerifyChildSources(
      Map<String, ?> parameters) {
    Long id = ((Number) parameters.get("v")).longValue();
    return java.util.stream.Stream.of(
            cycleMethodsJpa.findSourceIdsByVersion(id),
            admissionChoicesJpa.findSourceIdsByVersion(id),
            admissionCutoffsJpa.findSourceIdsByVersion(id))
        .flatMap(Collection::stream)
        .distinct()
        .map(v -> Map.<String, Object>of("id", v.toString()))
        .toList();
  }

  public Map<String, Object> findApiVerifiedSourcesForVerifyChildSources(
      Map<String, ?> parameters) {
    return verifiedSource(((Number) parameters.get("id")).longValue());
  }

  private final vn.edu.utc.rag.persistence.internal.EntityViews views;

  private final vn.edu.utc.rag.persistence.repository.AdmissionChoicesRepository
      admissionChoicesJpa;

  private final vn.edu.utc.rag.persistence.repository.AdmissionCutoffsRepository
      admissionCutoffsJpa;

  private final vn.edu.utc.rag.persistence.repository.AdmissionOptionCombinationsRepository
      admissionOptionCombinationsJpa;

  private final vn.edu.utc.rag.persistence.repository.AdmissionOptionsRepository
      admissionOptionsJpa;

  private final vn.edu.utc.rag.persistence.repository.CampusesRepository campusesJpa;

  private final vn.edu.utc.rag.persistence.repository.CurriculumCoursesRepository
      curriculumCoursesJpa;

  private final vn.edu.utc.rag.persistence.repository.CycleMethodsRepository cycleMethodsJpa;

  private final vn.edu.utc.rag.persistence.repository.DocumentRevisionsRepository
      documentRevisionsJpa;

  private final vn.edu.utc.rag.persistence.repository.GuideEntriesRepository guideEntriesJpa;

  private final vn.edu.utc.rag.persistence.repository.ProgramOfferingsRepository
      programOfferingsJpa;

  private final vn.edu.utc.rag.persistence.repository.ScoreRuleBandsRepository scoreRuleBandsJpa;

  private final vn.edu.utc.rag.persistence.repository.ScoreRuleInputsRepository scoreRuleInputsJpa;

  public Map<String, Object> insertIdentity(
      vn.edu.utc.rag.editorial.EditorialKind k, Map<String, Object> fields) {
    return switch (k.table()) {
      case "admission_cycles" -> writer.createAdmissionCycles(fields);
      case "program_offerings" -> writer.createProgramOfferings(fields);
      case "guide_items" -> writer.createGuideItems(fields);
      case "financial_policies" -> writer.createFinancialPolicies(fields);
      case "score_rules" -> writer.createScoreRules(fields);
      default -> throw new IllegalArgumentException("Unsupported editorial family");
    };
  }

  public Map<String, Object> insertDraft(
      vn.edu.utc.rag.editorial.EditorialKind k, Map<String, Object> fields) {
    return switch (k.versions()) {
      case "admission_cycle_versions" -> writer.createAdmissionCycleVersions(fields);
      case "curriculum_versions" -> writer.createCurriculumVersions(fields);
      case "guide_versions" -> writer.createGuideVersions(fields);
      case "financial_policy_versions" -> writer.createFinancialPolicyVersions(fields);
      case "score_rule_versions" -> writer.createScoreRuleVersions(fields);
      default -> throw new IllegalArgumentException("Unsupported editorial family");
    };
  }

  public Map<String, Object> insertChild(String table, Map<String, Object> fields) {
    return switch (table) {
      case "cycle_methods" -> writer.createCycleMethods(fields);
      case "admission_choices" -> writer.createAdmissionChoices(fields);
      case "admission_options" -> writer.createAdmissionOptions(fields);
      case "admission_option_combinations" -> writer.createAdmissionOptionCombinations(fields);
      case "admission_cutoffs" -> writer.createAdmissionCutoffs(fields);
      case "curriculum_courses" -> writer.createCurriculumCourses(fields);
      case "guide_entries" -> writer.createGuideEntries(fields);
      case "score_rule_inputs" -> writer.createScoreRuleInputs(fields);
      case "score_rule_bands" -> writer.createScoreRuleBands(fields);
      case "schedule_events" -> writer.createScheduleEvents(fields);
      default -> throw new IllegalArgumentException("Unsupported child collection");
    };
  }

  public Map<String, Object> createAdmissionOptions(Map<String, Object> fields) {
    return writer.createAdmissionOptions(fields);
  }

  private final vn.edu.utc.rag.persistence.query.QueryPages queryPages;

  public Map<String, Object> findPageForList(
      Map<String, ?> input,
      vn.edu.utc.rag.persistence.query.QueryPage req,
      vn.edu.utc.rag.editorial.EditorialKind k,
      vn.edu.utc.rag.persistence.query.CampusScope scope) {
    Map<String, Object> parameters = new HashMap<>(input);
    String filter =
        vn.edu.utc.rag.persistence.query.QueryPages.filters(
            req,
            parameters,
            Map.of(
                "year",
                k.identity().contains("entry_year") ? "t.entry_year" : "t.admission_year",
                "campus_code",
                "c.code"));
    return queryPages.page(
        "SELECT t.*,c.code AS campus_code FROM "
            + k.table()
            + " t JOIN campuses c ON c.id=t.campus_id WHERE "
            + scope.predicate("t.campus_id")
            + filter,
        parameters,
        req,
        "id:bigint:asc");
  }

  public Map<String, Object> findPageForVersions(
      Map<String, ?> input,
      vn.edu.utc.rag.persistence.query.QueryPage req,
      vn.edu.utc.rag.editorial.EditorialKind k) {
    Map<String, Object> parameters = new HashMap<>(input);
    String filter =
        vn.edu.utc.rag.persistence.query.QueryPages.filters(
            req, parameters, Map.of("status", "status"));
    return queryPages.page(
        "SELECT * FROM " + k.versions() + " WHERE " + k.parent() + "=:parent" + filter,
        parameters,
        req,
        "id:bigint:asc");
  }

  private final EditorialVersionsRepository versions;

  private Map<String, Object> verifiedSource(Long id) {
    var source =
        documentRevisionsJpa.findById(id).orElseThrow(vn.edu.utc.rag.common.ApiException::missing);
    if (!Boolean.TRUE.equals(source.getSourceVerified()) || source.getRevokedAt() != null)
      throw vn.edu.utc.rag.common.ApiException.missing();
    return Map.of("id", source.getId().toString());
  }

  private final vn.edu.utc.rag.persistence.repository.ScheduleEventsRepository scheduleEventsJpa;
}
