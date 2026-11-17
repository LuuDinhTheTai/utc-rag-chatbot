package vn.edu.utc.rag.service.editorial;

import static vn.edu.utc.rag.common.http.EntityTags.*;

import jakarta.servlet.http.HttpServletRequest;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.utc.rag.domain.editorial.EditorialKind;
import vn.edu.utc.rag.exception.ApiException;
import vn.edu.utc.rag.persistence.repository.*;
import vn.edu.utc.rag.persistence.repository.query.EditorialQueryRepository;
import vn.edu.utc.rag.security.*;
import vn.edu.utc.rag.validation.EditorialInput;
import vn.edu.utc.rag.validation.Input;
import vn.edu.utc.rag.web.pagination.PageRequestFactory;

@Service
@Transactional()
public class EditorialService {

  public static final Map<String, EditorialKind> KINDS = EditorialKind.ALL;

  private final EditorialInput write;

  private final Permissions permissions;

  private final PageRequestFactory pages;

  public EditorialService(
      EditorialInput write,
      Permissions permissions,
      PageRequestFactory pages,
      EditorialQueryRepository repository,
      vn.edu.utc.rag.service.audit.AuditService audit,
      vn.edu.utc.rag.mapper.common.EntityMapper views,
      vn.edu.utc.rag.persistence.repository.CampusesRepository campusesJpa,
      vn.edu.utc.rag.mapper.editorial.EditorialMapper editorialMapper,
      vn.edu.utc.rag.persistence.repository.CycleMethodsRepository cycleMethodsJpa,
      vn.edu.utc.rag.persistence.repository.AdmissionChoicesRepository admissionChoicesJpa,
      vn.edu.utc.rag.persistence.repository.AdmissionOptionsRepository admissionOptionsJpa,
      vn.edu.utc.rag.persistence.repository.AdmissionOptionCombinationsRepository
          admissionOptionCombinationsJpa,
      vn.edu.utc.rag.persistence.repository.AdmissionCutoffsRepository admissionCutoffsJpa,
      vn.edu.utc.rag.persistence.repository.CurriculumCoursesRepository curriculumCoursesJpa,
      vn.edu.utc.rag.persistence.repository.GuideEntriesRepository guideEntriesJpa,
      vn.edu.utc.rag.persistence.repository.ScoreRuleInputsRepository scoreRuleInputsJpa,
      vn.edu.utc.rag.persistence.repository.ScoreRuleBandsRepository scoreRuleBandsJpa,
      vn.edu.utc.rag.persistence.repository.ScheduleEventsRepository scheduleEventsJpa,
      vn.edu.utc.rag.persistence.repository.DocumentRevisionsRepository documentRevisionsJpa,
      vn.edu.utc.rag.persistence.repository.ProgramOfferingsRepository programOfferingsJpa,
      AdmissionCyclesRepository admissionCycles,
      ProgramOfferingsRepository programOfferings,
      GuideItemsRepository guideItems,
      FinancialPoliciesRepository financialPolicies,
      ScoreRulesRepository scoreRules,
      AdmissionCycleVersionsRepository admissionCycleVersions,
      CurriculumVersionsRepository curriculumVersions,
      GuideVersionsRepository guideVersions,
      FinancialPolicyVersionsRepository financialPolicyVersions,
      ScoreRuleVersionsRepository scoreRuleVersions) {
    this.write = write;
    this.permissions = permissions;
    this.pages = pages;
    this.repository = repository;
    this.audit = audit;
    this.views = views;
    this.campusesJpa = campusesJpa;
    this.editorialMapper = editorialMapper;
    this.cycleMethodsJpa = cycleMethodsJpa;
    this.admissionChoicesJpa = admissionChoicesJpa;
    this.admissionOptionsJpa = admissionOptionsJpa;
    this.admissionOptionCombinationsJpa = admissionOptionCombinationsJpa;
    this.admissionCutoffsJpa = admissionCutoffsJpa;
    this.curriculumCoursesJpa = curriculumCoursesJpa;
    this.guideEntriesJpa = guideEntriesJpa;
    this.scoreRuleInputsJpa = scoreRuleInputsJpa;
    this.scoreRuleBandsJpa = scoreRuleBandsJpa;
    this.scheduleEventsJpa = scheduleEventsJpa;
    this.documentRevisionsJpa = documentRevisionsJpa;
    this.programOfferingsJpa = programOfferingsJpa;
    this.admissionCycles = admissionCycles;
    this.programOfferings = programOfferings;
    this.guideItems = guideItems;
    this.financialPolicies = financialPolicies;
    this.scoreRules = scoreRules;
    this.admissionCycleVersions = admissionCycleVersions;
    this.curriculumVersions = curriculumVersions;
    this.guideVersions = guideVersions;
    this.financialPolicyVersions = financialPolicyVersions;
    this.scoreRuleVersions = scoreRuleVersions;
  }

  public EditorialKind kind(String key) {
    var k = KINDS.get(key);
    if (k == null) throw ApiException.missing();
    return k;
  }

  public Map<String, Object> item(
      String key, long id, HttpServletRequest req, boolean lock, String... roles) {
    var k = kind(key);
    var row = findRecordsForItem(Map.of("id", id), k, lock);
    permissions.check(req, row.get("campus_id"), roles);
    return row;
  }

  public Object list(String key, HttpServletRequest req) {
    var k = kind(key);
    var scope = permissions.campusScope(req, "STAFF_EDITOR", "STAFF_REVIEWER");
    return repository.findPageForList(Map.of(), pages.request(req), k, scope);
  }

  @Transactional
  public Map<String, Object> create(String key, Map<String, Object> body, HttpServletRequest req) {
    var k = kind(key);
    var normalized = new HashMap<>(body);
    if (normalized.containsKey("year")) normalized.put("admission_year", normalized.remove("year"));
    Set<String> allowed =
        new HashSet<>(
            List.of((k.identity() + "," + k.optionalIdentity() + ",campus_code").split(",")));
    if (!allowed.containsAll(normalized.keySet())) throw ApiException.invalid("Unknown field.");
    String code = Input.text(normalized, "campus_code");
    var campus = findCampusesForCreate(Map.of("code", code));
    permissions.check(req, campus.get("id"), "STAFF_EDITOR");
    var fields = write.fields(normalized, k.identity(), k.optionalIdentity());
    fields.put("campus_id", Input.id(campus.get("id")));
    for (String reference : List.of("cycle_id", "program_offering_id"))
      if (fields.get(reference) != null) {
        String table = reference.equals("cycle_id") ? "admission_cycles" : "program_offerings";
        var target = findRecordsForCreate(Map.of("id", fields.get(reference)), table);
        Object year = fields.getOrDefault("admission_year", fields.get("entry_year"));
        if (!target.get("campus_id").toString().equals(campus.get("id").toString())
            || !target.get("year").toString().equals(year.toString()))
          throw ApiException.invalid("Referenced item must belong to the same campus and year.");
      }
    var row = insertIdentity(k, fields);
    audit.record(
        permissions.check(req, campus.get("id"), "STAFF_EDITOR"),
        "CREATE",
        k.table(),
        row.get("id"),
        Map.of());
    return row;
  }

  public Object versions(String key, long id, HttpServletRequest req) {
    var k = kind(key);
    item(key, id, req, false, "STAFF_EDITOR", "STAFF_REVIEWER");
    return repository.findPageForVersions(Map.of("parent", id), pages.request(req), k);
  }

  public Map<String, Object> version(String key, long id, long version, HttpServletRequest req) {
    var k = kind(key);
    item(key, id, req, false, "STAFF_EDITOR", "STAFF_REVIEWER");
    var row = findRecordsForVersion(Map.of("v", version, "id", id), k);
    row.putAll(children(k, version));
    return row;
  }

  private Map<String, Object> children(EditorialKind k, long version) {
    var p = Map.<String, Object>of("v", version);
    var result = new LinkedHashMap<String, Object>();
    if (k.table().equals("admission_cycles")) {
      result.put("methods", findCycleMethodsForChildren(p));
      var choices = findAdmissionChoicesForChildren(p);
      for (var choice : choices) {
        var options = findAdmissionOptionsForChildren(Map.of("id", Input.id(choice.get("id"))));
        for (var option : options) {
          option.put(
              "combinations",
              findAdmissionOptionCombinationsForChildren(Map.of("id", Input.id(option.get("id")))));
          option.put(
              "cutoffs", findAdmissionCutoffsForChildren(Map.of("id", Input.id(option.get("id")))));
        }
        choice.put("options", options);
      }
      result.put("choices", choices);
    }
    if (k.children().contains("courses"))
      result.put("courses", findCurriculumCoursesForChildren(p));
    if (k.children().contains("entries")) result.put("entries", findGuideEntriesForChildren(p));
    if (k.children().contains("inputs")) {
      var inputs = findScoreRuleInputsForChildren(p);
      for (var input : inputs)
        input.put("bands", findScoreRuleBandsForChildren(Map.of("id", Input.id(input.get("id")))));
      result.put("inputs", inputs);
    }
    if (k.children().contains("schedule_events"))
      result.put("schedule_events", findRecordsForChildren(p, k));
    return result;
  }

  private void checkSource(long id, Object campus) {
    var source = findDocumentRevisionsForCheckSource(Map.of("id", id));
    if (source.get("revoked_at") != null) throw ApiException.conflict("Source is revoked.");
    if (source.get("campus_id") != null
        && !source.get("campus_id").toString().equals(campus.toString()))
      throw ApiException.invalid("Source belongs to another campus.");
  }

  @Transactional
  public Map<String, Object> save(
      String key,
      long id,
      Long version,
      String expected,
      Map<String, Object> body,
      HttpServletRequest req) {
    var k = kind(key);
    var parent = item(key, id, req, true, "STAFF_EDITOR");
    Set<String> allowed = new HashSet<>(k.children());
    allowed.addAll(List.of((k.draft() + "," + k.optionalDraft()).split(",")));
    if (!allowed.containsAll(body.keySet())) throw ApiException.invalid("Unknown draft field.");
    var fields = write.fields(body, k.draft(), k.optionalDraft());
    checkSource((Long) fields.get("source_revision_id"), parent.get("campus_id"));
    if (version == null) {
      fields.put(k.parent(), id);
      fields.put("status", "DRAFT");
      fields.put("updated_at", OffsetDateTime.now());
      var max = findRecordsForSave(Map.of("id", id), k);
      fields.put("version_no", max.get("n"));
      version = Input.id(insertDraft(k, fields).get("id"));
    } else {
      var old = findRecordsForSave2(Map.of("v", version, "id", id), k);
      match(expected, old.get("lock_version"), "version");
      if (!"DRAFT".equals(old.get("status")))
        throw ApiException.conflict("Only drafts can be edited.");
      fields.put("v", version);
      applyRecordsForSave(fields, k);
      deleteChildren(k, version);
    }
    putChildren(k, version, body, parent.get("campus_id"));
    audit.record(
        permissions.check(req, parent.get("campus_id"), "STAFF_EDITOR"),
        "SAVE_DRAFT",
        k.versions(),
        version,
        Map.of());
    return version(key, id, version, req);
  }

  private void deleteChildren(EditorialKind k, long v) {
    var p = Map.<String, Object>of("v", v);
    if (k.table().equals("admission_cycles")) {
      applyAdmissionCutoffsForDeleteChildren(p);
      applyAdmissionOptionCombinationsForDeleteChildren(p);
      applyAdmissionOptionsForDeleteChildren(p);
      applyAdmissionChoicesForDeleteChildren(p);
      applyCycleMethodsForDeleteChildren(p);
    }
    if (k.children().contains("courses")) applyCurriculumCoursesForDeleteChildren(p);
    if (k.children().contains("entries")) applyGuideEntriesForDeleteChildren(p);
    if (k.children().contains("inputs")) {
      applyScoreRuleBandsForDeleteChildren(p);
      applyScoreRuleInputsForDeleteChildren(p);
    }
    if (k.children().contains("schedule_events")) applyRecordsForDeleteChildren(p, k);
  }

  private List<Map<String, Object>> optionalObjects(Map<String, Object> b, String key) {
    return b.containsKey(key) ? Input.objects(b, key) : List.of();
  }

  private Map<String, Object> child(
      String table,
      Map<String, Object> body,
      String required,
      String optional,
      String parent,
      long id) {
    var f = write.fields(body, required, optional);
    f.put(parent, id);
    return insertChild(table, f);
  }

  private void putChildren(EditorialKind k, long v, Map<String, Object> b, Object campus) {
    if (k.table().equals("admission_cycles")) {
      Map<Long, Long> methods = new HashMap<>();
      for (var m : optionalObjects(b, "methods")) {
        var r =
            child(
                "cycle_methods",
                m,
                "method_id,eligibility_text",
                "source_revision_id",
                "cycle_version_id",
                v);
        if (r.get("source_revision_id") != null)
          checkSource(Input.id(r.get("source_revision_id")), campus);
        if (methods.put(Input.id(r.get("method_id")), Input.id(r.get("id"))) != null)
          throw ApiException.invalid("Duplicate admission method.");
      }
      for (var c : optionalObjects(b, "choices")) {
        var offering =
            findProgramOfferingsForPutChildren(
                Map.of("id", Input.id(c.get("program_offering_id"))));
        if (!campus.toString().equals(offering.get("campus_id").toString()))
          throw ApiException.invalid("Offering belongs to another campus.");
        var choice =
            child(
                "admission_choices",
                c,
                "program_offering_id",
                "total_quota,source_revision_id",
                "cycle_version_id",
                v);
        if (choice.get("source_revision_id") != null)
          checkSource(Input.id(choice.get("source_revision_id")), campus);
        for (var o : optionalObjects(c, "options")) {
          Long method = methods.get(Input.id(o.get("method_id")));
          if (method == null) throw ApiException.invalid("Option method is not defined.");
          var f = write.fields(o, "", "method_quota,additional_conditions");
          f.put("cycle_version_id", v);
          f.put("choice_id", Input.id(choice.get("id")));
          f.put("cycle_method_id", method);
          long option = Input.id(createAdmissionOptions(f).get("id"));
          for (var comb : optionalObjects(o, "combinations"))
            child(
                "admission_option_combinations",
                comb,
                "combination_id",
                "conditions_text",
                "option_id",
                option);
          for (var cutoff : optionalObjects(o, "cutoffs")) {
            checkSource(Input.id(cutoff.get("source_revision_id")), campus);
            child(
                "admission_cutoffs",
                cutoff,
                "score,scale_code,source_revision_id",
                "combination_id",
                "option_id",
                option);
          }
        }
      }
    }
    for (var c : optionalObjects(b, "courses"))
      child(
          "curriculum_courses",
          c,
          "course_id,course_title,credits,course_group",
          "suggested_term",
          "curriculum_version_id",
          v);
    for (var e : optionalObjects(b, "entries"))
      child(
          "guide_entries",
          e,
          "position,entry_type,label",
          "instructions,link_url,is_required",
          "guide_version_id",
          v);
    for (var i : optionalObjects(b, "inputs")) {
      var input =
          child(
              "score_rule_inputs",
              i,
              "input_kind,input_code,label,is_required",
              "min_value,max_value",
              "rule_version_id",
              v);
      for (var band : optionalObjects(i, "bands"))
        child(
            "score_rule_bands",
            band,
            "converted_value",
            "match_code,lower_bound,upper_bound",
            "rule_input_id",
            Input.id(input.get("id")));
    }
    for (var e : optionalObjects(b, "schedule_events"))
      child(
          "schedule_events",
          e,
          "title,starts_at",
          "ends_at,location,notes",
          k.table().equals("admission_cycles") ? "cycle_version_id" : "guide_version_id",
          v);
  }

  public Map<String, Object> publication(String key, long id, HttpServletRequest req) {
    var k = kind(key);
    var parent = item(key, id, req, false, "STAFF_EDITOR", "STAFF_REVIEWER");
    var versions = findRecordsForPublication(Map.of("id", id), k);
    var result = new LinkedHashMap<String, Object>();
    result.put("version_id", versions.isEmpty() ? null : versions.getFirst().get("version_id"));
    result.put("published_at", versions.isEmpty() ? null : versions.getFirst().get("published_at"));
    result.put("publication_version", parent.get("publication_version"));
    return result;
  }

  @Transactional
  public Map<String, Object> publish(
      String key, long id, String expected, Map<String, Object> body, HttpServletRequest req) {
    Input.only(body, "version_id");
    if (!body.containsKey("version_id"))
      throw ApiException.invalid("version_id is required (or null).");
    var k = kind(key);
    var parent = item(key, id, req, true, "STAFF_REVIEWER");
    match(expected, parent.get("publication_version"), "publication");
    Long v = body.get("version_id") == null ? null : Input.id(body.get("version_id"));
    if (v != null) {
      var version = findRecordsForPublish(Map.of("v", v, "id", id), k);
      checkSource(Input.id(version.get("source_revision_id")), parent.get("campus_id"));
      findApiVerifiedSourcesForPublish(Map.of("id", Input.id(version.get("source_revision_id"))));
      verifyChildSources(k, v);
    }
    applyRecordsForPublish(Map.of("id", id), k);
    if (v != null)
      applyRecordsForPublish2(
          Map.of("u", permissions.check(req, parent.get("campus_id"), "STAFF_REVIEWER"), "id", v),
          k);
    applyRecordsForPublish3(Map.of("id", id), k);
    audit.record(
        permissions.check(req, parent.get("campus_id"), "STAFF_REVIEWER"),
        "PUBLISH",
        k.table(),
        id,
        body);
    return publication(key, id, req);
  }

  private void verifyChildSources(EditorialKind k, long v) {
    if (!k.table().equals("admission_cycles")) return;
    var sources = findCycleMethodsForVerifyChildSources(Map.of("v", v));
    for (var s : sources)
      findApiVerifiedSourcesForVerifyChildSources(Map.of("id", Input.id(s.get("id"))));
  }

  private final EditorialQueryRepository repository;

  private final vn.edu.utc.rag.service.audit.AuditService audit;

  private Map<String, Object> findRecordsForItem(
      Map<String, ?> parameters, vn.edu.utc.rag.domain.editorial.EditorialKind k, boolean lock) {
    return item(k, ((Number) parameters.get("id")).longValue(), lock);
  }

  private Map<String, Object> findCampusesForCreate(Map<String, ?> parameters) {
    var result = campusesJpa.findAllByCode((String) views.value(parameters, "code", String.class));
    var rows = result.stream().map(e -> views.project(e, "id")).toList();
    if (rows.isEmpty()) throw vn.edu.utc.rag.exception.ApiException.missing();
    return rows.getFirst();
  }

  private final vn.edu.utc.rag.mapper.common.EntityMapper views;

  private final vn.edu.utc.rag.persistence.repository.CampusesRepository campusesJpa;

  private Map<String, Object> findRecordsForCreate(Map<String, ?> parameters, String table) {
    var kind =
        table.equals("admission_cycles")
            ? EditorialKind.ALL.get("admission-cycles")
            : EditorialKind.ALL.get("program-offerings");
    var row = item(kind, ((Number) parameters.get("id")).longValue(), false);
    return Map.of(
        "campus_id",
        row.get("campus_id"),
        "year",
        row.get(table.equals("admission_cycles") ? "admission_year" : "entry_year"));
  }

  private Map<String, Object> insertIdentity(
      vn.edu.utc.rag.domain.editorial.EditorialKind k, Map<String, Object> fields) {
    return switch (k.table()) {
      case "admission_cycles" ->
          views.of(admissionCycles.saveAndFlush(editorialMapper.toAdmissionCycles(fields)));
      case "program_offerings" ->
          views.of(programOfferingsJpa.saveAndFlush(editorialMapper.toProgramOfferings(fields)));
      case "guide_items" -> views.of(guideItems.saveAndFlush(editorialMapper.toGuideItems(fields)));
      case "financial_policies" ->
          views.of(financialPolicies.saveAndFlush(editorialMapper.toFinancialPolicies(fields)));
      case "score_rules" -> views.of(scoreRules.saveAndFlush(editorialMapper.toScoreRules(fields)));
      default -> throw new IllegalArgumentException("Unsupported editorial family");
    };
  }

  private final vn.edu.utc.rag.mapper.editorial.EditorialMapper editorialMapper;

  private Map<String, Object> findRecordsForVersion(
      Map<String, ?> parameters, vn.edu.utc.rag.domain.editorial.EditorialKind k) {
    return version(
        k,
        ((Number) parameters.get("v")).longValue(),
        ((Number) parameters.get("id")).longValue(),
        false);
  }

  private List<Map<String, Object>> findCycleMethodsForChildren(Map<String, ?> parameters) {
    var result =
        cycleMethodsJpa.findAllByCycleVersionIdOrderByIdAsc(
            (Long) views.value(parameters, "v", Long.class));
    var rows = result.stream().map(e -> views.project(e, "*")).toList();
    return rows;
  }

  private final vn.edu.utc.rag.persistence.repository.CycleMethodsRepository cycleMethodsJpa;

  private List<Map<String, Object>> findAdmissionChoicesForChildren(Map<String, ?> parameters) {
    var result =
        admissionChoicesJpa.findAllByCycleVersionIdOrderByIdAsc(
            (Long) views.value(parameters, "v", Long.class));
    var rows = result.stream().map(e -> views.project(e, "*")).toList();
    return rows;
  }

  private final vn.edu.utc.rag.persistence.repository.AdmissionChoicesRepository
      admissionChoicesJpa;

  private List<Map<String, Object>> findAdmissionOptionsForChildren(Map<String, ?> parameters) {
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

  private final vn.edu.utc.rag.persistence.repository.AdmissionOptionsRepository
      admissionOptionsJpa;

  private List<Map<String, Object>> findAdmissionOptionCombinationsForChildren(
      Map<String, ?> parameters) {
    var result =
        admissionOptionCombinationsJpa.findAllByOptionIdOrderByCombinationIdAsc(
            (Long) views.value(parameters, "id", Long.class));
    var rows = result.stream().map(e -> views.project(e, "*")).toList();
    return rows;
  }

  private final vn.edu.utc.rag.persistence.repository.AdmissionOptionCombinationsRepository
      admissionOptionCombinationsJpa;

  private List<Map<String, Object>> findAdmissionCutoffsForChildren(Map<String, ?> parameters) {
    var result =
        admissionCutoffsJpa.findAllByOptionIdOrderByIdAsc(
            (Long) views.value(parameters, "id", Long.class));
    var rows = result.stream().map(e -> views.project(e, "*")).toList();
    return rows;
  }

  private final vn.edu.utc.rag.persistence.repository.AdmissionCutoffsRepository
      admissionCutoffsJpa;

  private List<Map<String, Object>> findCurriculumCoursesForChildren(Map<String, ?> parameters) {
    var result =
        curriculumCoursesJpa.findAllByCurriculumVersionIdOrderByCourseIdAsc(
            (Long) views.value(parameters, "v", Long.class));
    var rows = result.stream().map(e -> views.project(e, "*")).toList();
    return rows;
  }

  private final vn.edu.utc.rag.persistence.repository.CurriculumCoursesRepository
      curriculumCoursesJpa;

  private List<Map<String, Object>> findGuideEntriesForChildren(Map<String, ?> parameters) {
    var result =
        guideEntriesJpa.findAllByGuideVersionIdOrderByPositionAscIdAsc(
            (Long) views.value(parameters, "v", Long.class));
    var rows = result.stream().map(e -> views.project(e, "*")).toList();
    return rows;
  }

  private final vn.edu.utc.rag.persistence.repository.GuideEntriesRepository guideEntriesJpa;

  private List<Map<String, Object>> findScoreRuleInputsForChildren(Map<String, ?> parameters) {
    var result =
        scoreRuleInputsJpa.findAllByRuleVersionIdOrderByIdAsc(
            (Long) views.value(parameters, "v", Long.class));
    var rows = result.stream().map(e -> views.project(e, "*")).toList();
    return rows;
  }

  private final vn.edu.utc.rag.persistence.repository.ScoreRuleInputsRepository scoreRuleInputsJpa;

  private List<Map<String, Object>> findScoreRuleBandsForChildren(Map<String, ?> parameters) {
    var result =
        scoreRuleBandsJpa.findAllByRuleInputIdOrderByIdAsc(
            (Long) views.value(parameters, "id", Long.class));
    var rows = result.stream().map(e -> views.project(e, "*")).toList();
    return rows;
  }

  private final vn.edu.utc.rag.persistence.repository.ScoreRuleBandsRepository scoreRuleBandsJpa;

  private List<Map<String, Object>> findRecordsForChildren(
      Map<String, ?> parameters, vn.edu.utc.rag.domain.editorial.EditorialKind k) {
    Long id = ((Number) parameters.get("v")).longValue();
    return (k.table().equals("admission_cycles")
            ? scheduleEventsJpa.findAllByCycleVersionIdOrderByStartsAtAscIdAsc(id)
            : scheduleEventsJpa.findAllByGuideVersionIdOrderByStartsAtAscIdAsc(id))
        .stream().map(views::of).toList();
  }

  private final vn.edu.utc.rag.persistence.repository.ScheduleEventsRepository scheduleEventsJpa;

  private Map<String, Object> findDocumentRevisionsForCheckSource(Map<String, ?> parameters) {
    var result =
        documentRevisionsJpa.findLockedById((Long) views.value(parameters, "id", Long.class));
    var rows = result.stream().map(e -> views.project(e, "campus_id,revoked_at")).toList();
    if (rows.isEmpty()) throw vn.edu.utc.rag.exception.ApiException.missing();
    return rows.getFirst();
  }

  private final vn.edu.utc.rag.persistence.repository.DocumentRevisionsRepository
      documentRevisionsJpa;

  private Map<String, Object> findRecordsForSave(
      Map<String, ?> parameters, vn.edu.utc.rag.domain.editorial.EditorialKind k) {
    return Map.of("n", nextVersion(k, ((Number) parameters.get("id")).longValue()));
  }

  private Map<String, Object> insertDraft(
      vn.edu.utc.rag.domain.editorial.EditorialKind k, Map<String, Object> fields) {
    return switch (k.versions()) {
      case "admission_cycle_versions" ->
          views.of(
              admissionCycleVersions.saveAndFlush(
                  editorialMapper.toAdmissionCycleVersions(fields)));
      case "curriculum_versions" ->
          views.of(curriculumVersions.saveAndFlush(editorialMapper.toCurriculumVersions(fields)));
      case "guide_versions" ->
          views.of(guideVersions.saveAndFlush(editorialMapper.toGuideVersions(fields)));
      case "financial_policy_versions" ->
          views.of(
              financialPolicyVersions.saveAndFlush(
                  editorialMapper.toFinancialPolicyVersions(fields)));
      case "score_rule_versions" ->
          views.of(scoreRuleVersions.saveAndFlush(editorialMapper.toScoreRuleVersions(fields)));
      default -> throw new IllegalArgumentException("Unsupported editorial family");
    };
  }

  private Map<String, Object> findRecordsForSave2(
      Map<String, ?> parameters, vn.edu.utc.rag.domain.editorial.EditorialKind k) {
    return version(
        k,
        ((Number) parameters.get("v")).longValue(),
        ((Number) parameters.get("id")).longValue(),
        true);
  }

  private int applyRecordsForSave(
      Map<String, ?> parameters, vn.edu.utc.rag.domain.editorial.EditorialKind k) {
    updateDraft(k, ((Number) parameters.get("v")).longValue(), parameters);
    return 1;
  }

  private int applyAdmissionCutoffsForDeleteChildren(Map<String, ?> parameters) {
    return admissionCutoffsJpa.deleteByCycleVersion(((Number) parameters.get("v")).longValue());
  }

  private int applyAdmissionOptionCombinationsForDeleteChildren(Map<String, ?> parameters) {
    return admissionOptionCombinationsJpa.deleteByCycleVersion(
        ((Number) parameters.get("v")).longValue());
  }

  private int applyAdmissionOptionsForDeleteChildren(Map<String, ?> parameters) {
    {
      int count =
          admissionOptionsJpa.deleteByCycleVersionId(
              (Long) views.value(parameters, "v", Long.class));
      admissionOptionsJpa.flush();
      return count;
    }
  }

  private int applyAdmissionChoicesForDeleteChildren(Map<String, ?> parameters) {
    {
      int count =
          admissionChoicesJpa.deleteByCycleVersionId(
              (Long) views.value(parameters, "v", Long.class));
      admissionChoicesJpa.flush();
      return count;
    }
  }

  private int applyCycleMethodsForDeleteChildren(Map<String, ?> parameters) {
    {
      int count =
          cycleMethodsJpa.deleteByCycleVersionId((Long) views.value(parameters, "v", Long.class));
      cycleMethodsJpa.flush();
      return count;
    }
  }

  private int applyCurriculumCoursesForDeleteChildren(Map<String, ?> parameters) {
    {
      int count =
          curriculumCoursesJpa.deleteByCurriculumVersionId(
              (Long) views.value(parameters, "v", Long.class));
      curriculumCoursesJpa.flush();
      return count;
    }
  }

  private int applyGuideEntriesForDeleteChildren(Map<String, ?> parameters) {
    {
      int count =
          guideEntriesJpa.deleteByGuideVersionId((Long) views.value(parameters, "v", Long.class));
      guideEntriesJpa.flush();
      return count;
    }
  }

  private int applyScoreRuleBandsForDeleteChildren(Map<String, ?> parameters) {
    return scoreRuleBandsJpa.deleteByRuleVersion(((Number) parameters.get("v")).longValue());
  }

  private int applyScoreRuleInputsForDeleteChildren(Map<String, ?> parameters) {
    {
      int count =
          scoreRuleInputsJpa.deleteByRuleVersionId((Long) views.value(parameters, "v", Long.class));
      scoreRuleInputsJpa.flush();
      return count;
    }
  }

  private int applyRecordsForDeleteChildren(
      Map<String, ?> parameters, vn.edu.utc.rag.domain.editorial.EditorialKind k) {
    Long id = ((Number) parameters.get("v")).longValue();
    if (k.table().equals("admission_cycles")) scheduleEventsJpa.deleteByCycleVersionId(id);
    else scheduleEventsJpa.deleteByGuideVersionId(id);
    return 1;
  }

  private Map<String, Object> insertChild(String table, Map<String, Object> fields) {
    return switch (table) {
      case "cycle_methods" ->
          views.of(cycleMethodsJpa.saveAndFlush(editorialMapper.toCycleMethods(fields)));
      case "admission_choices" ->
          views.of(admissionChoicesJpa.saveAndFlush(editorialMapper.toAdmissionChoices(fields)));
      case "admission_options" ->
          views.of(admissionOptionsJpa.saveAndFlush(editorialMapper.toAdmissionOptions(fields)));
      case "admission_option_combinations" ->
          views.of(
              admissionOptionCombinationsJpa.saveAndFlush(
                  editorialMapper.toAdmissionOptionCombinations(fields)));
      case "admission_cutoffs" ->
          views.of(admissionCutoffsJpa.saveAndFlush(editorialMapper.toAdmissionCutoffs(fields)));
      case "curriculum_courses" ->
          views.of(curriculumCoursesJpa.saveAndFlush(editorialMapper.toCurriculumCourses(fields)));
      case "guide_entries" ->
          views.of(guideEntriesJpa.saveAndFlush(editorialMapper.toGuideEntries(fields)));
      case "score_rule_inputs" ->
          views.of(scoreRuleInputsJpa.saveAndFlush(editorialMapper.toScoreRuleInputs(fields)));
      case "score_rule_bands" ->
          views.of(scoreRuleBandsJpa.saveAndFlush(editorialMapper.toScoreRuleBands(fields)));
      case "schedule_events" ->
          views.of(scheduleEventsJpa.saveAndFlush(editorialMapper.toScheduleEvents(fields)));
      default -> throw new IllegalArgumentException("Unsupported child collection");
    };
  }

  private Map<String, Object> findProgramOfferingsForPutChildren(Map<String, ?> parameters) {
    var result = programOfferingsJpa.findById((Long) views.value(parameters, "id", Long.class));
    var rows = result.stream().map(e -> views.project(e, "campus_id")).toList();
    if (rows.isEmpty()) throw vn.edu.utc.rag.exception.ApiException.missing();
    return rows.getFirst();
  }

  private final vn.edu.utc.rag.persistence.repository.ProgramOfferingsRepository
      programOfferingsJpa;

  private Map<String, Object> createAdmissionOptions(Map<String, Object> fields) {
    return views.of(admissionOptionsJpa.saveAndFlush(editorialMapper.toAdmissionOptions(fields)));
  }

  private List<Map<String, Object>> findRecordsForPublication(
      Map<String, ?> parameters, vn.edu.utc.rag.domain.editorial.EditorialKind k) {
    return publication(k, ((Number) parameters.get("id")).longValue());
  }

  private Map<String, Object> findRecordsForPublish(
      Map<String, ?> parameters, vn.edu.utc.rag.domain.editorial.EditorialKind k) {
    return version(
        k,
        ((Number) parameters.get("v")).longValue(),
        ((Number) parameters.get("id")).longValue(),
        true);
  }

  private Map<String, Object> findApiVerifiedSourcesForPublish(Map<String, ?> parameters) {
    return verifiedSource(((Number) parameters.get("id")).longValue());
  }

  private int applyRecordsForPublish(
      Map<String, ?> parameters, vn.edu.utc.rag.domain.editorial.EditorialKind k) {
    archivePublished(k, ((Number) parameters.get("id")).longValue());
    return 1;
  }

  private int applyRecordsForPublish2(
      Map<String, ?> parameters, vn.edu.utc.rag.domain.editorial.EditorialKind k) {
    publish(k, ((Number) parameters.get("id")).longValue(), (UUID) parameters.get("u"));
    return 1;
  }

  private int applyRecordsForPublish3(
      Map<String, ?> parameters, vn.edu.utc.rag.domain.editorial.EditorialKind k) {
    incrementPublication(k, ((Number) parameters.get("id")).longValue());
    return 1;
  }

  private List<Map<String, Object>> findCycleMethodsForVerifyChildSources(
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

  private Map<String, Object> findApiVerifiedSourcesForVerifyChildSources(
      Map<String, ?> parameters) {
    return verifiedSource(((Number) parameters.get("id")).longValue());
  }

  private Map<String, Object> verifiedSource(Long id) {
    var source =
        documentRevisionsJpa
            .findById(id)
            .orElseThrow(vn.edu.utc.rag.exception.ApiException::missing);
    if (!Boolean.TRUE.equals(source.getSourceVerified()) || source.getRevokedAt() != null)
      throw vn.edu.utc.rag.exception.ApiException.missing();
    return Map.of("id", source.getId().toString());
  }

  private Map<String, Object> item(EditorialKind kind, Long id, boolean lock) {
    return switch (kind.table()) {
      case "admission_cycles" ->
          views.of(
              (lock ? admissionCycles.findLockedById(id) : admissionCycles.findById(id))
                  .orElseThrow(ApiException::missing));
      case "program_offerings" ->
          views.of(
              (lock ? programOfferings.findLockedById(id) : programOfferings.findById(id))
                  .orElseThrow(ApiException::missing));
      case "guide_items" ->
          views.of(
              (lock ? guideItems.findLockedById(id) : guideItems.findById(id))
                  .orElseThrow(ApiException::missing));
      case "financial_policies" ->
          views.of(
              (lock ? financialPolicies.findLockedById(id) : financialPolicies.findById(id))
                  .orElseThrow(ApiException::missing));
      case "score_rules" ->
          views.of(
              (lock ? scoreRules.findLockedById(id) : scoreRules.findById(id))
                  .orElseThrow(ApiException::missing));
      default -> throw new IllegalArgumentException("Unknown editorial kind");
    };
  }

  private final AdmissionCyclesRepository admissionCycles;

  private final ProgramOfferingsRepository programOfferings;

  private final GuideItemsRepository guideItems;

  private final FinancialPoliciesRepository financialPolicies;

  private final ScoreRulesRepository scoreRules;

  private Map<String, Object> version(EditorialKind kind, Long id, Long parent, boolean lock) {
    switch (kind.versions()) {
      case "admission_cycle_versions" -> {
        var e =
            (lock ? admissionCycleVersions.findLockedById(id) : admissionCycleVersions.findById(id))
                .orElseThrow(ApiException::missing);
        if (!e.getCycleId().equals(parent)) throw ApiException.missing();
        return views.of(e);
      }
      case "curriculum_versions" -> {
        var e =
            (lock ? curriculumVersions.findLockedById(id) : curriculumVersions.findById(id))
                .orElseThrow(ApiException::missing);
        if (!e.getProgramOfferingId().equals(parent)) throw ApiException.missing();
        return views.of(e);
      }
      case "guide_versions" -> {
        var e =
            (lock ? guideVersions.findLockedById(id) : guideVersions.findById(id))
                .orElseThrow(ApiException::missing);
        if (!e.getGuideId().equals(parent)) throw ApiException.missing();
        return views.of(e);
      }
      case "financial_policy_versions" -> {
        var e =
            (lock
                    ? financialPolicyVersions.findLockedById(id)
                    : financialPolicyVersions.findById(id))
                .orElseThrow(ApiException::missing);
        if (!e.getPolicyId().equals(parent)) throw ApiException.missing();
        return views.of(e);
      }
      case "score_rule_versions" -> {
        var e =
            (lock ? scoreRuleVersions.findLockedById(id) : scoreRuleVersions.findById(id))
                .orElseThrow(ApiException::missing);
        if (!e.getScoreRuleId().equals(parent)) throw ApiException.missing();
        return views.of(e);
      }
      default -> throw new IllegalArgumentException("Unknown editorial kind");
    }
  }

  private final AdmissionCycleVersionsRepository admissionCycleVersions;

  private final CurriculumVersionsRepository curriculumVersions;

  private final GuideVersionsRepository guideVersions;

  private final FinancialPolicyVersionsRepository financialPolicyVersions;

  private final ScoreRuleVersionsRepository scoreRuleVersions;

  private Integer nextVersion(EditorialKind kind, Long parent) {
    return switch (kind.versions()) {
      case "admission_cycle_versions" -> admissionCycleVersions.nextVersionNumber(parent);
      case "curriculum_versions" -> curriculumVersions.nextVersionNumber(parent);
      case "guide_versions" -> guideVersions.nextVersionNumber(parent);
      case "financial_policy_versions" -> financialPolicyVersions.nextVersionNumber(parent);
      case "score_rule_versions" -> scoreRuleVersions.nextVersionNumber(parent);
      default -> throw new IllegalArgumentException("Unknown editorial kind");
    };
  }

  private void updateDraft(EditorialKind kind, Long id, Map<String, ?> fields) {
    switch (kind.versions()) {
      case "admission_cycle_versions" -> {
        var e = admissionCycleVersions.findById(id).orElseThrow(ApiException::missing);
        if (fields.containsKey("source_revision_id"))
          e.setSourceRevisionId((Long) views.value(fields, "source_revision_id", Long.class));
        e.setUpdatedAt(Instant.now());
        e.setLockVersion(e.getLockVersion() + 1);
      }
      case "curriculum_versions" -> {
        var e = curriculumVersions.findById(id).orElseThrow(ApiException::missing);
        if (fields.containsKey("source_revision_id"))
          e.setSourceRevisionId((Long) views.value(fields, "source_revision_id", Long.class));
        if (fields.containsKey("duration_months"))
          e.setDurationMonths((Short) views.value(fields, "duration_months", Short.class));
        if (fields.containsKey("total_credits"))
          e.setTotalCredits((Short) views.value(fields, "total_credits", Short.class));
        if (fields.containsKey("objectives"))
          e.setObjectives((String) views.value(fields, "objectives", String.class));
        if (fields.containsKey("learning_outcomes"))
          e.setLearningOutcomes((String) views.value(fields, "learning_outcomes", String.class));
        if (fields.containsKey("career_paths"))
          e.setCareerPaths((String) views.value(fields, "career_paths", String.class));
        e.setUpdatedAt(Instant.now());
        e.setLockVersion(e.getLockVersion() + 1);
      }
      case "guide_versions" -> {
        var e = guideVersions.findById(id).orElseThrow(ApiException::missing);
        if (fields.containsKey("source_revision_id"))
          e.setSourceRevisionId((Long) views.value(fields, "source_revision_id", Long.class));
        if (fields.containsKey("title"))
          e.setTitle((String) views.value(fields, "title", String.class));
        if (fields.containsKey("body"))
          e.setBody((String) views.value(fields, "body", String.class));
        e.setUpdatedAt(Instant.now());
        e.setLockVersion(e.getLockVersion() + 1);
      }
      case "financial_policy_versions" -> {
        var e = financialPolicyVersions.findById(id).orElseThrow(ApiException::missing);
        if (fields.containsKey("source_revision_id"))
          e.setSourceRevisionId((Long) views.value(fields, "source_revision_id", Long.class));
        if (fields.containsKey("title"))
          e.setTitle((String) views.value(fields, "title", String.class));
        if (fields.containsKey("body"))
          e.setBody((String) views.value(fields, "body", String.class));
        if (fields.containsKey("amount_vnd"))
          e.setAmountVnd((Long) views.value(fields, "amount_vnd", Long.class));
        if (fields.containsKey("charge_unit"))
          e.setChargeUnit((String) views.value(fields, "charge_unit", String.class));
        if (fields.containsKey("eligibility_text"))
          e.setEligibilityText((String) views.value(fields, "eligibility_text", String.class));
        if (fields.containsKey("due_at"))
          e.setDueAt((Instant) views.value(fields, "due_at", Instant.class));
        e.setUpdatedAt(Instant.now());
        e.setLockVersion(e.getLockVersion() + 1);
      }
      case "score_rule_versions" -> {
        var e = scoreRuleVersions.findById(id).orElseThrow(ApiException::missing);
        if (fields.containsKey("source_revision_id"))
          e.setSourceRevisionId((Long) views.value(fields, "source_revision_id", Long.class));
        if (fields.containsKey("formula_key"))
          e.setFormulaKey((String) views.value(fields, "formula_key", String.class));
        if (fields.containsKey("formula_config"))
          e.setFormulaConfig(
              (Map<String, Object>) views.value(fields, "formula_config", Map.class));
        if (fields.containsKey("output_scale_code"))
          e.setOutputScaleCode((String) views.value(fields, "output_scale_code", String.class));
        e.setUpdatedAt(Instant.now());
        e.setLockVersion(e.getLockVersion() + 1);
      }
      default -> throw new IllegalArgumentException("Unknown editorial kind");
    }
  }

  private List<Map<String, Object>> publication(EditorialKind kind, Long parent) {
    return switch (kind.versions()) {
      case "admission_cycle_versions" ->
          admissionCycleVersions.findAllByCycleIdAndStatus(parent, "PUBLISHED").stream()
              .map(e -> views.project(e, "id AS version_id,published_at"))
              .toList();
      case "curriculum_versions" ->
          curriculumVersions.findAllByProgramOfferingIdAndStatus(parent, "PUBLISHED").stream()
              .map(e -> views.project(e, "id AS version_id,published_at"))
              .toList();
      case "guide_versions" ->
          guideVersions.findAllByGuideIdAndStatus(parent, "PUBLISHED").stream()
              .map(e -> views.project(e, "id AS version_id,published_at"))
              .toList();
      case "financial_policy_versions" ->
          financialPolicyVersions.findAllByPolicyIdAndStatus(parent, "PUBLISHED").stream()
              .map(e -> views.project(e, "id AS version_id,published_at"))
              .toList();
      case "score_rule_versions" ->
          scoreRuleVersions.findAllByScoreRuleIdAndStatus(parent, "PUBLISHED").stream()
              .map(e -> views.project(e, "id AS version_id,published_at"))
              .toList();
      default -> throw new IllegalArgumentException("Unknown editorial kind");
    };
  }

  private void archivePublished(EditorialKind kind, Long parent) {
    switch (kind.versions()) {
      case "admission_cycle_versions" -> {
        for (var e : admissionCycleVersions.findAllByCycleIdAndStatus(parent, "PUBLISHED")) {
          e.setStatus("ARCHIVED");
          e.setUpdatedAt(Instant.now());
          e.setLockVersion(e.getLockVersion() + 1);
        }
        admissionCycleVersions.flush();
      }
      case "curriculum_versions" -> {
        for (var e : curriculumVersions.findAllByProgramOfferingIdAndStatus(parent, "PUBLISHED")) {
          e.setStatus("ARCHIVED");
          e.setUpdatedAt(Instant.now());
          e.setLockVersion(e.getLockVersion() + 1);
        }
        curriculumVersions.flush();
      }
      case "guide_versions" -> {
        for (var e : guideVersions.findAllByGuideIdAndStatus(parent, "PUBLISHED")) {
          e.setStatus("ARCHIVED");
          e.setUpdatedAt(Instant.now());
          e.setLockVersion(e.getLockVersion() + 1);
        }
        guideVersions.flush();
      }
      case "financial_policy_versions" -> {
        for (var e : financialPolicyVersions.findAllByPolicyIdAndStatus(parent, "PUBLISHED")) {
          e.setStatus("ARCHIVED");
          e.setUpdatedAt(Instant.now());
          e.setLockVersion(e.getLockVersion() + 1);
        }
        financialPolicyVersions.flush();
      }
      case "score_rule_versions" -> {
        for (var e : scoreRuleVersions.findAllByScoreRuleIdAndStatus(parent, "PUBLISHED")) {
          e.setStatus("ARCHIVED");
          e.setUpdatedAt(Instant.now());
          e.setLockVersion(e.getLockVersion() + 1);
        }
        scoreRuleVersions.flush();
      }
      default -> throw new IllegalArgumentException("Unknown editorial kind");
    }
  }

  private void publish(EditorialKind kind, Long id, UUID reviewer) {
    switch (kind.versions()) {
      case "admission_cycle_versions" -> {
        var e = admissionCycleVersions.findById(id).orElseThrow(ApiException::missing);
        e.setStatus("PUBLISHED");
        e.setReviewedBy(reviewer);
        e.setPublishedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        e.setLockVersion(e.getLockVersion() + 1);
      }
      case "curriculum_versions" -> {
        var e = curriculumVersions.findById(id).orElseThrow(ApiException::missing);
        e.setStatus("PUBLISHED");
        e.setReviewedBy(reviewer);
        e.setPublishedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        e.setLockVersion(e.getLockVersion() + 1);
      }
      case "guide_versions" -> {
        var e = guideVersions.findById(id).orElseThrow(ApiException::missing);
        e.setStatus("PUBLISHED");
        e.setReviewedBy(reviewer);
        e.setPublishedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        e.setLockVersion(e.getLockVersion() + 1);
      }
      case "financial_policy_versions" -> {
        var e = financialPolicyVersions.findById(id).orElseThrow(ApiException::missing);
        e.setStatus("PUBLISHED");
        e.setReviewedBy(reviewer);
        e.setPublishedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        e.setLockVersion(e.getLockVersion() + 1);
      }
      case "score_rule_versions" -> {
        var e = scoreRuleVersions.findById(id).orElseThrow(ApiException::missing);
        e.setStatus("PUBLISHED");
        e.setReviewedBy(reviewer);
        e.setPublishedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        e.setLockVersion(e.getLockVersion() + 1);
      }
      default -> throw new IllegalArgumentException("Unknown editorial kind");
    }
  }

  private void incrementPublication(EditorialKind kind, Long id) {
    switch (kind.table()) {
      case "admission_cycles" -> {
        var e = admissionCycles.findById(id).orElseThrow(ApiException::missing);
        e.setPublicationVersion(e.getPublicationVersion() + 1);
      }
      case "program_offerings" -> {
        var e = programOfferings.findById(id).orElseThrow(ApiException::missing);
        e.setPublicationVersion(e.getPublicationVersion() + 1);
      }
      case "guide_items" -> {
        var e = guideItems.findById(id).orElseThrow(ApiException::missing);
        e.setPublicationVersion(e.getPublicationVersion() + 1);
      }
      case "financial_policies" -> {
        var e = financialPolicies.findById(id).orElseThrow(ApiException::missing);
        e.setPublicationVersion(e.getPublicationVersion() + 1);
      }
      case "score_rules" -> {
        var e = scoreRules.findById(id).orElseThrow(ApiException::missing);
        e.setPublicationVersion(e.getPublicationVersion() + 1);
      }
      default -> throw new IllegalArgumentException("Unknown editorial kind");
    }
  }
}
