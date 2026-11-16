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
  public record Kind(
      String table,
      String versions,
      String parent,
      String identity,
      String optionalIdentity,
      String draft,
      String optionalDraft,
      Set<String> children) {}

  public static final Map<String, Kind> KINDS =
      Map.of(
          "admission-cycles",
              new Kind(
                  "admission_cycles",
                  "admission_cycle_versions",
                  "cycle_id",
                  "admission_year,round_code",
                  "",
                  "source_revision_id",
                  "",
                  Set.of("methods", "choices", "schedule_events")),
          "program-offerings",
              new Kind(
                  "program_offerings",
                  "curriculum_versions",
                  "program_offering_id",
                  "program_id,entry_year",
                  "",
                  "source_revision_id,duration_months,total_credits,objectives,learning_outcomes,career_paths",
                  "",
                  Set.of("courses")),
          "guide-items",
              new Kind(
                  "guide_items",
                  "guide_versions",
                  "guide_id",
                  "admission_year,guide_type,audience_code,guide_slug",
                  "cycle_id,method_id",
                  "source_revision_id,title,body",
                  "",
                  Set.of("entries", "schedule_events")),
          "financial-policies",
              new Kind(
                  "financial_policies",
                  "financial_policy_versions",
                  "policy_id",
                  "entry_year,policy_type,policy_code",
                  "program_offering_id",
                  "source_revision_id,title,body",
                  "amount_vnd,charge_unit,eligibility_text,due_at",
                  Set.of()),
          "score-rules",
              new Kind(
                  "score_rules",
                  "score_rule_versions",
                  "score_rule_id",
                  "admission_year,method_id",
                  "",
                  "source_revision_id,formula_key,formula_config,output_scale_code",
                  "",
                  Set.of("inputs")));
  private final Db db;
  private final SqlWrite write;
  private final Permissions permissions;
  private final Pages pages;

  public EditorialService(Db db, SqlWrite write, Permissions permissions, Pages pages) {
    this.db = db;
    this.write = write;
    this.permissions = permissions;
    this.pages = pages;
  }

  public Kind kind(String key) {
    var k = KINDS.get(key);
    if (k == null) throw ApiException.missing();
    return k;
  }

  public Map<String, Object> item(
      String key, long id, HttpServletRequest req, boolean lock, String... roles) {
    var k = kind(key);
    var row =
        db.one(
            "SELECT * FROM " + k.table + " WHERE id=:id" + (lock ? " FOR UPDATE" : ""),
            Map.of("id", id));
    permissions.check(req, row.get("campus_id"), roles);
    return row;
  }

  public Object list(String key, HttpServletRequest req) {
    var k = kind(key);
    String scope = permissions.campusFilter(req, "t.campus_id", "STAFF_EDITOR", "STAFF_REVIEWER");
    var args = new HashMap<String, Object>();
    String filter =
        Pages.filters(
            req,
            args,
            Map.of(
                "year",
                k.identity.contains("entry_year") ? "t.entry_year" : "t.admission_year",
                "campus_code",
                "c.code"));
    return pages.page(
        "SELECT t.*,c.code AS campus_code FROM "
            + k.table
            + " t JOIN campuses c ON c.id=t.campus_id WHERE "
            + scope
            + filter,
        args,
        req,
        "id:bigint:asc");
  }

  @Transactional
  public Map<String, Object> create(String key, Map<String, Object> body, HttpServletRequest req) {
    var k = kind(key);
    var normalized = new HashMap<>(body);
    if (normalized.containsKey("year")) normalized.put("admission_year", normalized.remove("year"));
    Set<String> allowed =
        new HashSet<>(List.of((k.identity + "," + k.optionalIdentity + ",campus_code").split(",")));
    if (!allowed.containsAll(normalized.keySet())) throw ApiException.invalid("Unknown field.");
    String code = Input.text(normalized, "campus_code");
    var campus = db.one("SELECT id FROM campuses WHERE code=:code", Map.of("code", code));
    permissions.check(req, campus.get("id"), "STAFF_EDITOR");
    var fields = write.fields(normalized, k.identity, k.optionalIdentity);
    fields.put("campus_id", Input.id(campus.get("id")));
    for (String reference : List.of("cycle_id", "program_offering_id"))
      if (fields.get(reference) != null) {
        String table = reference.equals("cycle_id") ? "admission_cycles" : "program_offerings";
        var target =
            db.one(
                "SELECT campus_id,"
                    + (table.equals("admission_cycles") ? "admission_year" : "entry_year")
                    + " AS year FROM "
                    + table
                    + " WHERE id=:id",
                Map.of("id", fields.get(reference)));
        Object year = fields.getOrDefault("admission_year", fields.get("entry_year"));
        if (!target.get("campus_id").toString().equals(campus.get("id").toString())
            || !target.get("year").toString().equals(year.toString()))
          throw ApiException.invalid("Referenced item must belong to the same campus and year.");
      }
    var row = write.insert(k.table, fields);
    db.audit(
        permissions.check(req, campus.get("id"), "STAFF_EDITOR"),
        "CREATE",
        k.table,
        row.get("id"),
        Map.of());
    return row;
  }

  public Object versions(String key, long id, HttpServletRequest req) {
    var k = kind(key);
    item(key, id, req, false, "STAFF_EDITOR", "STAFF_REVIEWER");
    var args = new HashMap<String, Object>(Map.of("parent", id));
    String filter = Pages.filters(req, args, Map.of("status", "status"));
    return pages.page(
        "SELECT * FROM " + k.versions + " WHERE " + k.parent + "=:parent" + filter,
        args,
        req,
        "id:bigint:asc");
  }

  public Map<String, Object> version(String key, long id, long version, HttpServletRequest req) {
    var k = kind(key);
    item(key, id, req, false, "STAFF_EDITOR", "STAFF_REVIEWER");
    var row =
        db.one(
            "SELECT * FROM " + k.versions + " WHERE id=:v AND " + k.parent + "=:id",
            Map.of("v", version, "id", id));
    row.putAll(children(k, version));
    return row;
  }

  private Map<String, Object> children(Kind k, long version) {
    var p = Map.<String, Object>of("v", version);
    var result = new LinkedHashMap<String, Object>();
    if (k.table.equals("admission_cycles")) {
      result.put(
          "methods",
          db.list("SELECT * FROM cycle_methods WHERE cycle_version_id=:v ORDER BY id", p));
      var choices =
          db.list("SELECT * FROM admission_choices WHERE cycle_version_id=:v ORDER BY id", p);
      for (var choice : choices) {
        var options =
            db.list(
                "SELECT o.*,cm.method_id FROM admission_options o JOIN cycle_methods cm ON"
                    + " cm.id=o.cycle_method_id WHERE o.choice_id=:id ORDER BY o.id",
                Map.of("id", Input.id(choice.get("id"))));
        for (var option : options) {
          option.put(
              "combinations",
              db.list(
                  "SELECT * FROM admission_option_combinations WHERE option_id=:id ORDER BY"
                      + " combination_id",
                  Map.of("id", Input.id(option.get("id")))));
          option.put(
              "cutoffs",
              db.list(
                  "SELECT * FROM admission_cutoffs WHERE option_id=:id ORDER BY id",
                  Map.of("id", Input.id(option.get("id")))));
        }
        choice.put("options", options);
      }
      result.put("choices", choices);
    }
    if (k.children.contains("courses"))
      result.put(
          "courses",
          db.list(
              "SELECT * FROM curriculum_courses WHERE curriculum_version_id=:v ORDER BY course_id",
              p));
    if (k.children.contains("entries"))
      result.put(
          "entries",
          db.list("SELECT * FROM guide_entries WHERE guide_version_id=:v ORDER BY position,id", p));
    if (k.children.contains("inputs")) {
      var inputs =
          db.list("SELECT * FROM score_rule_inputs WHERE rule_version_id=:v ORDER BY id", p);
      for (var input : inputs)
        input.put(
            "bands",
            db.list(
                "SELECT * FROM score_rule_bands WHERE rule_input_id=:id ORDER BY id",
                Map.of("id", Input.id(input.get("id")))));
      result.put("inputs", inputs);
    }
    if (k.children.contains("schedule_events"))
      result.put(
          "schedule_events",
          db.list(
              "SELECT * FROM schedule_events WHERE "
                  + (k.table.equals("admission_cycles") ? "cycle_version_id" : "guide_version_id")
                  + "=:v ORDER BY starts_at,id",
              p));
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
    var source =
        db.one(
            "SELECT campus_id,revoked_at FROM document_revisions WHERE id=:id FOR SHARE",
            Map.of("id", id));
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
    Set<String> allowed = new HashSet<>(k.children);
    allowed.addAll(List.of((k.draft + "," + k.optionalDraft).split(",")));
    if (!allowed.containsAll(body.keySet())) throw ApiException.invalid("Unknown draft field.");
    var fields = write.fields(body, k.draft, k.optionalDraft);
    checkSource((Long) fields.get("source_revision_id"), parent.get("campus_id"));
    if (version == null) {
      fields.put(k.parent, id);
      fields.put("status", "DRAFT");
      fields.put("updated_at", OffsetDateTime.now());
      var max =
          db.one(
              "SELECT coalesce(max(version_no),0)+1 AS n FROM "
                  + k.versions
                  + " WHERE "
                  + k.parent
                  + "=:id",
              Map.of("id", id));
      fields.put("version_no", max.get("n"));
      version = Input.id(write.insert(k.versions, fields).get("id"));
    } else {
      var old =
          db.one(
              "SELECT * FROM " + k.versions + " WHERE id=:v AND " + k.parent + "=:id FOR UPDATE",
              Map.of("v", version, "id", id));
      match(expected, old.get("lock_version"), "version");
      if (!"DRAFT".equals(old.get("status")))
        throw ApiException.conflict("Only drafts can be edited.");
      fields.put("v", version);
      String setters =
          fields.keySet().stream()
              .filter(s -> !s.equals("v"))
              .map(s -> s + "=" + (s.endsWith("_config") ? "CAST(:" + s + " AS jsonb)" : ":" + s))
              .collect(java.util.stream.Collectors.joining(","));
      db.update(
          "UPDATE "
              + k.versions
              + " SET "
              + setters
              + ",updated_at=now(),lock_version=lock_version+1 WHERE id=:v",
          fields);
      deleteChildren(k, version);
    }
    putChildren(k, version, body, parent.get("campus_id"));
    db.audit(
        permissions.check(req, parent.get("campus_id"), "STAFF_EDITOR"),
        "SAVE_DRAFT",
        k.versions,
        version,
        Map.of());
    return version(key, id, version, req);
  }

  private void deleteChildren(Kind k, long v) {
    var p = Map.<String, Object>of("v", v);
    if (k.table.equals("admission_cycles")) {
      db.update(
          "DELETE FROM admission_cutoffs WHERE option_id IN (SELECT id FROM admission_options WHERE"
              + " cycle_version_id=:v)",
          p);
      db.update(
          "DELETE FROM admission_option_combinations WHERE option_id IN (SELECT id FROM"
              + " admission_options WHERE cycle_version_id=:v)",
          p);
      db.update("DELETE FROM admission_options WHERE cycle_version_id=:v", p);
      db.update("DELETE FROM admission_choices WHERE cycle_version_id=:v", p);
      db.update("DELETE FROM cycle_methods WHERE cycle_version_id=:v", p);
    }
    if (k.children.contains("courses"))
      db.update("DELETE FROM curriculum_courses WHERE curriculum_version_id=:v", p);
    if (k.children.contains("entries"))
      db.update("DELETE FROM guide_entries WHERE guide_version_id=:v", p);
    if (k.children.contains("inputs")) {
      db.update(
          "DELETE FROM score_rule_bands WHERE rule_input_id IN (SELECT id FROM score_rule_inputs"
              + " WHERE rule_version_id=:v)",
          p);
      db.update("DELETE FROM score_rule_inputs WHERE rule_version_id=:v", p);
    }
    if (k.children.contains("schedule_events"))
      db.update(
          "DELETE FROM schedule_events WHERE "
              + (k.table.equals("admission_cycles") ? "cycle_version_id" : "guide_version_id")
              + "=:v",
          p);
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
    return write.insert(table, f);
  }

  private void putChildren(Kind k, long v, Map<String, Object> b, Object campus) {
    if (k.table.equals("admission_cycles")) {
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
            db.one(
                "SELECT campus_id FROM program_offerings WHERE id=:id",
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
          long option = Input.id(write.insert("admission_options", f).get("id"));
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
          k.table.equals("admission_cycles") ? "cycle_version_id" : "guide_version_id",
          v);
  }

  public Map<String, Object> publication(String key, long id, HttpServletRequest req) {
    var k = kind(key);
    var parent = item(key, id, req, false, "STAFF_EDITOR", "STAFF_REVIEWER");
    var versions =
        db.list(
            "SELECT id AS version_id,published_at FROM "
                + k.versions
                + " WHERE "
                + k.parent
                + "=:id AND status='PUBLISHED'",
            Map.of("id", id));
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
      var version =
          db.one(
              "SELECT * FROM " + k.versions + " WHERE id=:v AND " + k.parent + "=:id FOR UPDATE",
              Map.of("v", v, "id", id));
      checkSource(Input.id(version.get("source_revision_id")), parent.get("campus_id"));
      db.one(
          "SELECT id FROM api_verified_sources WHERE id=:id",
          Map.of("id", Input.id(version.get("source_revision_id"))));
      verifyChildSources(k, v);
    }
    db.update(
        "UPDATE "
            + k.versions
            + " SET status='ARCHIVED',lock_version=lock_version+1,updated_at=now() WHERE "
            + k.parent
            + "=:id AND status='PUBLISHED'",
        Map.of("id", id));
    if (v != null)
      db.update(
          "UPDATE "
              + k.versions
              + " SET status='PUBLISHED',reviewed_by=:u,published_at=now(),updated_at=now(),lock_version=lock_version+1"
              + " WHERE id=:id",
          Map.of("u", permissions.check(req, parent.get("campus_id"), "STAFF_REVIEWER"), "id", v));
    db.update(
        "UPDATE " + k.table + " SET publication_version=publication_version+1 WHERE id=:id",
        Map.of("id", id));
    db.audit(
        permissions.check(req, parent.get("campus_id"), "STAFF_REVIEWER"),
        "PUBLISH",
        k.table,
        id,
        body);
    return publication(key, id, req);
  }

  private void verifyChildSources(Kind k, long v) {
    if (!k.table.equals("admission_cycles")) return;
    var sources =
        db.list(
            "SELECT source_revision_id AS id FROM cycle_methods WHERE cycle_version_id=:v AND"
                + " source_revision_id IS NOT NULL UNION SELECT source_revision_id FROM"
                + " admission_choices WHERE cycle_version_id=:v AND source_revision_id IS NOT NULL"
                + " UNION SELECT c.source_revision_id FROM admission_cutoffs c JOIN"
                + " admission_options o ON o.id=c.option_id WHERE o.cycle_version_id=:v",
            Map.of("v", v));
    for (var s : sources)
      db.one(
          "SELECT id FROM api_verified_sources WHERE id=:id", Map.of("id", Input.id(s.get("id"))));
  }
}
