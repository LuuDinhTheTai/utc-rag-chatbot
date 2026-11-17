package vn.edu.utc.rag.editorial;

import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.utc.rag.common.*;
import vn.edu.utc.rag.security.*;

@Service
public class EditorialService {

  public static final Map<String, EditorialKind> KINDS = EditorialKind.ALL;

  private final DraftFields write;

  private final Permissions permissions;

  private final Pages pages;

  public EditorialService(
      DraftFields write,
      Permissions permissions,
      Pages pages,
      EditorialDataRepository repository,
      vn.edu.utc.rag.persistence.repository.AuditRepository audit) {
    this.write = write;
    this.permissions = permissions;
    this.pages = pages;
    this.repository = repository;
    this.audit = audit;
  }

  public EditorialKind kind(String key) {
    var k = KINDS.get(key);
    if (k == null) throw ApiException.missing();
    return k;
  }

  public Map<String, Object> item(
      String key, long id, HttpServletRequest req, boolean lock, String... roles) {
    var k = kind(key);
    var row = repository.findRecordsForItem(Map.of("id", id), k, lock);
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
    var campus = repository.findCampusesForCreate(Map.of("code", code));
    permissions.check(req, campus.get("id"), "STAFF_EDITOR");
    var fields = write.fields(normalized, k.identity(), k.optionalIdentity());
    fields.put("campus_id", Input.id(campus.get("id")));
    for (String reference : List.of("cycle_id", "program_offering_id"))
      if (fields.get(reference) != null) {
        String table = reference.equals("cycle_id") ? "admission_cycles" : "program_offerings";
        var target = repository.findRecordsForCreate(Map.of("id", fields.get(reference)), table);
        Object year = fields.getOrDefault("admission_year", fields.get("entry_year"));
        if (!target.get("campus_id").toString().equals(campus.get("id").toString())
            || !target.get("year").toString().equals(year.toString()))
          throw ApiException.invalid("Referenced item must belong to the same campus and year.");
      }
    var row = repository.insertIdentity(k, fields);
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
    var row = repository.findRecordsForVersion(Map.of("v", version, "id", id), k);
    row.putAll(children(k, version));
    return row;
  }

  private Map<String, Object> children(EditorialKind k, long version) {
    var p = Map.<String, Object>of("v", version);
    var result = new LinkedHashMap<String, Object>();
    if (k.table().equals("admission_cycles")) {
      result.put("methods", repository.findCycleMethodsForChildren(p));
      var choices = repository.findAdmissionChoicesForChildren(p);
      for (var choice : choices) {
        var options =
            repository.findAdmissionOptionsForChildren(Map.of("id", Input.id(choice.get("id"))));
        for (var option : options) {
          option.put(
              "combinations",
              repository.findAdmissionOptionCombinationsForChildren(
                  Map.of("id", Input.id(option.get("id")))));
          option.put(
              "cutoffs",
              repository.findAdmissionCutoffsForChildren(Map.of("id", Input.id(option.get("id")))));
        }
        choice.put("options", options);
      }
      result.put("choices", choices);
    }
    if (k.children().contains("courses"))
      result.put("courses", repository.findCurriculumCoursesForChildren(p));
    if (k.children().contains("entries"))
      result.put("entries", repository.findGuideEntriesForChildren(p));
    if (k.children().contains("inputs")) {
      var inputs = repository.findScoreRuleInputsForChildren(p);
      for (var input : inputs)
        input.put(
            "bands",
            repository.findScoreRuleBandsForChildren(Map.of("id", Input.id(input.get("id")))));
      result.put("inputs", inputs);
    }
    if (k.children().contains("schedule_events"))
      result.put("schedule_events", repository.findRecordsForChildren(p, k));
    return result;
  }

  public static String etag(Object value, String prefix) {
    return "\"" + prefix + "-" + value + "\"";
  }

  public static void match(String provided, Object value, String prefix) {
    if (provided == null)
      throw new ApiException(428, "PRECONDITION_REQUIRED", "If-Match is required.");
    if (!etag(value, prefix).equals(provided))
      throw new ApiException(412, "PRECONDITION_FAILED", "Resource has changed.");
  }

  private void checkSource(long id, Object campus) {
    var source = repository.findDocumentRevisionsForCheckSource(Map.of("id", id));
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
      var max = repository.findRecordsForSave(Map.of("id", id), k);
      fields.put("version_no", max.get("n"));
      version = Input.id(repository.insertDraft(k, fields).get("id"));
    } else {
      var old = repository.findRecordsForSave2(Map.of("v", version, "id", id), k);
      match(expected, old.get("lock_version"), "version");
      if (!"DRAFT".equals(old.get("status")))
        throw ApiException.conflict("Only drafts can be edited.");
      fields.put("v", version);
      repository.applyRecordsForSave(fields, k);
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
      repository.applyAdmissionCutoffsForDeleteChildren(p);
      repository.applyAdmissionOptionCombinationsForDeleteChildren(p);
      repository.applyAdmissionOptionsForDeleteChildren(p);
      repository.applyAdmissionChoicesForDeleteChildren(p);
      repository.applyCycleMethodsForDeleteChildren(p);
    }
    if (k.children().contains("courses")) repository.applyCurriculumCoursesForDeleteChildren(p);
    if (k.children().contains("entries")) repository.applyGuideEntriesForDeleteChildren(p);
    if (k.children().contains("inputs")) {
      repository.applyScoreRuleBandsForDeleteChildren(p);
      repository.applyScoreRuleInputsForDeleteChildren(p);
    }
    if (k.children().contains("schedule_events")) repository.applyRecordsForDeleteChildren(p, k);
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
    return repository.insertChild(table, f);
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
            repository.findProgramOfferingsForPutChildren(
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
          long option = Input.id(repository.createAdmissionOptions(f).get("id"));
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
    var versions = repository.findRecordsForPublication(Map.of("id", id), k);
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
      var version = repository.findRecordsForPublish(Map.of("v", v, "id", id), k);
      checkSource(Input.id(version.get("source_revision_id")), parent.get("campus_id"));
      repository.findApiVerifiedSourcesForPublish(
          Map.of("id", Input.id(version.get("source_revision_id"))));
      verifyChildSources(k, v);
    }
    repository.applyRecordsForPublish(Map.of("id", id), k);
    if (v != null)
      repository.applyRecordsForPublish2(
          Map.of("u", permissions.check(req, parent.get("campus_id"), "STAFF_REVIEWER"), "id", v),
          k);
    repository.applyRecordsForPublish3(Map.of("id", id), k);
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
    var sources = repository.findCycleMethodsForVerifyChildSources(Map.of("v", v));
    for (var s : sources)
      repository.findApiVerifiedSourcesForVerifyChildSources(Map.of("id", Input.id(s.get("id"))));
  }

  private final EditorialDataRepository repository;

  private final vn.edu.utc.rag.persistence.repository.AuditRepository audit;
}
