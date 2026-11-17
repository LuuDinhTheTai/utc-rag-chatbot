package vn.edu.utc.rag.editorial;

import java.util.*;

public record EditorialKind(
    String table,
    String versions,
    String parent,
    String identity,
    String optionalIdentity,
    String draft,
    String optionalDraft,
    Set<String> children) {
  public static final Map<String, EditorialKind> ALL =
      Map.of(
          "admission-cycles",
          new EditorialKind(
              "admission_cycles",
              "admission_cycle_versions",
              "cycle_id",
              "admission_year,round_code",
              "",
              "source_revision_id",
              "",
              Set.of("methods", "choices", "schedule_events")),
          "program-offerings",
          new EditorialKind(
              "program_offerings",
              "curriculum_versions",
              "program_offering_id",
              "program_id,entry_year",
              "",
              "source_revision_id,duration_months,total_credits,objectives,learning_outcomes,career_paths",
              "",
              Set.of("courses")),
          "guide-items",
          new EditorialKind(
              "guide_items",
              "guide_versions",
              "guide_id",
              "admission_year,guide_type,audience_code,guide_slug",
              "cycle_id,method_id",
              "source_revision_id,title,body",
              "",
              Set.of("entries", "schedule_events")),
          "financial-policies",
          new EditorialKind(
              "financial_policies",
              "financial_policy_versions",
              "policy_id",
              "entry_year,policy_type,policy_code",
              "program_offering_id",
              "source_revision_id,title,body",
              "amount_vnd,charge_unit,eligibility_text,due_at",
              Set.of()),
          "score-rules",
          new EditorialKind(
              "score_rules",
              "score_rule_versions",
              "score_rule_id",
              "admission_year,method_id",
              "",
              "source_revision_id,formula_key,formula_config,output_scale_code",
              "",
              Set.of("inputs")));
}
