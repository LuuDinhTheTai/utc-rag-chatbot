package vn.edu.utc.rag.editorial;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.utc.rag.common.ApiException;
import vn.edu.utc.rag.persistence.internal.EntityViews;
import vn.edu.utc.rag.persistence.repository.*;

@Repository
@Transactional
public class EditorialVersionsRepository {
  private final EntityViews views;
  private final AdmissionCyclesRepository admissionCycles;
  private final AdmissionCycleVersionsRepository admissionCycleVersions;
  private final ProgramOfferingsRepository programOfferings;
  private final CurriculumVersionsRepository curriculumVersions;
  private final GuideItemsRepository guideItems;
  private final GuideVersionsRepository guideVersions;
  private final FinancialPoliciesRepository financialPolicies;
  private final FinancialPolicyVersionsRepository financialPolicyVersions;
  private final ScoreRulesRepository scoreRules;
  private final ScoreRuleVersionsRepository scoreRuleVersions;

  public EditorialVersionsRepository(
      EntityViews views,
      AdmissionCyclesRepository admissionCycles,
      AdmissionCycleVersionsRepository admissionCycleVersions,
      ProgramOfferingsRepository programOfferings,
      CurriculumVersionsRepository curriculumVersions,
      GuideItemsRepository guideItems,
      GuideVersionsRepository guideVersions,
      FinancialPoliciesRepository financialPolicies,
      FinancialPolicyVersionsRepository financialPolicyVersions,
      ScoreRulesRepository scoreRules,
      ScoreRuleVersionsRepository scoreRuleVersions) {
    this.views = views;
    this.admissionCycles = admissionCycles;
    this.admissionCycleVersions = admissionCycleVersions;
    this.programOfferings = programOfferings;
    this.curriculumVersions = curriculumVersions;
    this.guideItems = guideItems;
    this.guideVersions = guideVersions;
    this.financialPolicies = financialPolicies;
    this.financialPolicyVersions = financialPolicyVersions;
    this.scoreRules = scoreRules;
    this.scoreRuleVersions = scoreRuleVersions;
  }

  public Map<String, Object> item(EditorialKind kind, Long id, boolean lock) {
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

  public Map<String, Object> version(EditorialKind kind, Long id, Long parent, boolean lock) {
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

  public Integer nextVersion(EditorialKind kind, Long parent) {
    return switch (kind.versions()) {
      case "admission_cycle_versions" -> admissionCycleVersions.nextVersionNumber(parent);
      case "curriculum_versions" -> curriculumVersions.nextVersionNumber(parent);
      case "guide_versions" -> guideVersions.nextVersionNumber(parent);
      case "financial_policy_versions" -> financialPolicyVersions.nextVersionNumber(parent);
      case "score_rule_versions" -> scoreRuleVersions.nextVersionNumber(parent);
      default -> throw new IllegalArgumentException("Unknown editorial kind");
    };
  }

  public void updateDraft(EditorialKind kind, Long id, Map<String, ?> fields) {
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

  public List<Map<String, Object>> publication(EditorialKind kind, Long parent) {
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

  public void archivePublished(EditorialKind kind, Long parent) {
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

  public void publish(EditorialKind kind, Long id, UUID reviewer) {
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

  public void incrementPublication(EditorialKind kind, Long id) {
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
