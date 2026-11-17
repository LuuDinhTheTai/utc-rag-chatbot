package vn.edu.utc.rag.editorial;

import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.utc.rag.persistence.entity.*;
import vn.edu.utc.rag.persistence.internal.EntityViews;
import vn.edu.utc.rag.persistence.repository.*;

@Repository
@Transactional
public class EditorialEntityWriter {
  private final EntityViews views;
  private final AdmissionCyclesRepository admissionCycles;
  private final ProgramOfferingsRepository programOfferings;
  private final GuideItemsRepository guideItems;
  private final FinancialPoliciesRepository financialPolicies;
  private final ScoreRulesRepository scoreRules;
  private final AdmissionCycleVersionsRepository admissionCycleVersions;
  private final CurriculumVersionsRepository curriculumVersions;
  private final GuideVersionsRepository guideVersions;
  private final FinancialPolicyVersionsRepository financialPolicyVersions;
  private final ScoreRuleVersionsRepository scoreRuleVersions;
  private final CycleMethodsRepository cycleMethods;
  private final AdmissionChoicesRepository admissionChoices;
  private final AdmissionOptionsRepository admissionOptions;
  private final AdmissionOptionCombinationsRepository admissionOptionCombinations;
  private final AdmissionCutoffsRepository admissionCutoffs;
  private final CurriculumCoursesRepository curriculumCourses;
  private final GuideEntriesRepository guideEntries;
  private final ScoreRuleInputsRepository scoreRuleInputs;
  private final ScoreRuleBandsRepository scoreRuleBands;
  private final ScheduleEventsRepository scheduleEvents;
  private final ProgramsRepository programs;

  public EditorialEntityWriter(
      EntityViews views,
      AdmissionCyclesRepository admissionCycles,
      ProgramOfferingsRepository programOfferings,
      GuideItemsRepository guideItems,
      FinancialPoliciesRepository financialPolicies,
      ScoreRulesRepository scoreRules,
      AdmissionCycleVersionsRepository admissionCycleVersions,
      CurriculumVersionsRepository curriculumVersions,
      GuideVersionsRepository guideVersions,
      FinancialPolicyVersionsRepository financialPolicyVersions,
      ScoreRuleVersionsRepository scoreRuleVersions,
      CycleMethodsRepository cycleMethods,
      AdmissionChoicesRepository admissionChoices,
      AdmissionOptionsRepository admissionOptions,
      AdmissionOptionCombinationsRepository admissionOptionCombinations,
      AdmissionCutoffsRepository admissionCutoffs,
      CurriculumCoursesRepository curriculumCourses,
      GuideEntriesRepository guideEntries,
      ScoreRuleInputsRepository scoreRuleInputs,
      ScoreRuleBandsRepository scoreRuleBands,
      ScheduleEventsRepository scheduleEvents,
      ProgramsRepository programs) {
    this.views = views;
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
    this.cycleMethods = cycleMethods;
    this.admissionChoices = admissionChoices;
    this.admissionOptions = admissionOptions;
    this.admissionOptionCombinations = admissionOptionCombinations;
    this.admissionCutoffs = admissionCutoffs;
    this.curriculumCourses = curriculumCourses;
    this.guideEntries = guideEntries;
    this.scoreRuleInputs = scoreRuleInputs;
    this.scoreRuleBands = scoreRuleBands;
    this.scheduleEvents = scheduleEvents;
    this.programs = programs;
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> createAdmissionCycles(Map<String, Object> fields) {
    var entity = new AdmissionCyclesEntity();
    if (fields.containsKey("id")) entity.setId((Long) views.value(fields, "id", Long.class));
    if (fields.containsKey("campus_id"))
      entity.setCampusId((Short) views.value(fields, "campus_id", Short.class));
    if (fields.containsKey("admission_year"))
      entity.setAdmissionYear((Short) views.value(fields, "admission_year", Short.class));
    if (fields.containsKey("round_code"))
      entity.setRoundCode((String) views.value(fields, "round_code", String.class));
    if (fields.containsKey("publication_version"))
      entity.setPublicationVersion((Long) views.value(fields, "publication_version", Long.class));
    return views.of(admissionCycles.saveAndFlush(entity));
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> createProgramOfferings(Map<String, Object> fields) {
    var entity = new ProgramOfferingsEntity();
    if (fields.containsKey("id")) entity.setId((Long) views.value(fields, "id", Long.class));
    if (fields.containsKey("program_id"))
      entity.setProgramId((Long) views.value(fields, "program_id", Long.class));
    if (fields.containsKey("campus_id"))
      entity.setCampusId((Short) views.value(fields, "campus_id", Short.class));
    if (fields.containsKey("entry_year"))
      entity.setEntryYear((Short) views.value(fields, "entry_year", Short.class));
    if (fields.containsKey("publication_version"))
      entity.setPublicationVersion((Long) views.value(fields, "publication_version", Long.class));
    return views.of(programOfferings.saveAndFlush(entity));
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> createGuideItems(Map<String, Object> fields) {
    var entity = new GuideItemsEntity();
    if (fields.containsKey("id")) entity.setId((Long) views.value(fields, "id", Long.class));
    if (fields.containsKey("campus_id"))
      entity.setCampusId((Short) views.value(fields, "campus_id", Short.class));
    if (fields.containsKey("admission_year"))
      entity.setAdmissionYear((Short) views.value(fields, "admission_year", Short.class));
    if (fields.containsKey("cycle_id"))
      entity.setCycleId((Long) views.value(fields, "cycle_id", Long.class));
    if (fields.containsKey("method_id"))
      entity.setMethodId((Short) views.value(fields, "method_id", Short.class));
    if (fields.containsKey("guide_type"))
      entity.setGuideType((String) views.value(fields, "guide_type", String.class));
    if (fields.containsKey("audience_code"))
      entity.setAudienceCode((String) views.value(fields, "audience_code", String.class));
    if (fields.containsKey("guide_slug"))
      entity.setGuideSlug((String) views.value(fields, "guide_slug", String.class));
    if (fields.containsKey("publication_version"))
      entity.setPublicationVersion((Long) views.value(fields, "publication_version", Long.class));
    return views.of(guideItems.saveAndFlush(entity));
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> createFinancialPolicies(Map<String, Object> fields) {
    var entity = new FinancialPoliciesEntity();
    if (fields.containsKey("id")) entity.setId((Long) views.value(fields, "id", Long.class));
    if (fields.containsKey("campus_id"))
      entity.setCampusId((Short) views.value(fields, "campus_id", Short.class));
    if (fields.containsKey("entry_year"))
      entity.setEntryYear((Short) views.value(fields, "entry_year", Short.class));
    if (fields.containsKey("program_offering_id"))
      entity.setProgramOfferingId((Long) views.value(fields, "program_offering_id", Long.class));
    if (fields.containsKey("policy_type"))
      entity.setPolicyType((String) views.value(fields, "policy_type", String.class));
    if (fields.containsKey("policy_code"))
      entity.setPolicyCode((String) views.value(fields, "policy_code", String.class));
    if (fields.containsKey("publication_version"))
      entity.setPublicationVersion((Long) views.value(fields, "publication_version", Long.class));
    return views.of(financialPolicies.saveAndFlush(entity));
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> createScoreRules(Map<String, Object> fields) {
    var entity = new ScoreRulesEntity();
    if (fields.containsKey("id")) entity.setId((Long) views.value(fields, "id", Long.class));
    if (fields.containsKey("campus_id"))
      entity.setCampusId((Short) views.value(fields, "campus_id", Short.class));
    if (fields.containsKey("admission_year"))
      entity.setAdmissionYear((Short) views.value(fields, "admission_year", Short.class));
    if (fields.containsKey("method_id"))
      entity.setMethodId((Short) views.value(fields, "method_id", Short.class));
    if (fields.containsKey("publication_version"))
      entity.setPublicationVersion((Long) views.value(fields, "publication_version", Long.class));
    return views.of(scoreRules.saveAndFlush(entity));
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> createAdmissionCycleVersions(Map<String, Object> fields) {
    var entity = new AdmissionCycleVersionsEntity();
    if (fields.containsKey("id")) entity.setId((Long) views.value(fields, "id", Long.class));
    if (fields.containsKey("cycle_id"))
      entity.setCycleId((Long) views.value(fields, "cycle_id", Long.class));
    if (fields.containsKey("version_no"))
      entity.setVersionNo((Integer) views.value(fields, "version_no", Integer.class));
    if (fields.containsKey("status"))
      entity.setStatus((String) views.value(fields, "status", String.class));
    if (fields.containsKey("source_revision_id"))
      entity.setSourceRevisionId((Long) views.value(fields, "source_revision_id", Long.class));
    if (fields.containsKey("reviewed_by"))
      entity.setReviewedBy((UUID) views.value(fields, "reviewed_by", UUID.class));
    if (fields.containsKey("published_at"))
      entity.setPublishedAt((Instant) views.value(fields, "published_at", Instant.class));
    if (fields.containsKey("updated_at"))
      entity.setUpdatedAt((Instant) views.value(fields, "updated_at", Instant.class));
    if (fields.containsKey("lock_version"))
      entity.setLockVersion((Long) views.value(fields, "lock_version", Long.class));
    return views.of(admissionCycleVersions.saveAndFlush(entity));
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> createCurriculumVersions(Map<String, Object> fields) {
    var entity = new CurriculumVersionsEntity();
    if (fields.containsKey("id")) entity.setId((Long) views.value(fields, "id", Long.class));
    if (fields.containsKey("program_offering_id"))
      entity.setProgramOfferingId((Long) views.value(fields, "program_offering_id", Long.class));
    if (fields.containsKey("version_no"))
      entity.setVersionNo((Integer) views.value(fields, "version_no", Integer.class));
    if (fields.containsKey("duration_months"))
      entity.setDurationMonths((Short) views.value(fields, "duration_months", Short.class));
    if (fields.containsKey("total_credits"))
      entity.setTotalCredits((Short) views.value(fields, "total_credits", Short.class));
    if (fields.containsKey("objectives"))
      entity.setObjectives((String) views.value(fields, "objectives", String.class));
    if (fields.containsKey("learning_outcomes"))
      entity.setLearningOutcomes((String) views.value(fields, "learning_outcomes", String.class));
    if (fields.containsKey("career_paths"))
      entity.setCareerPaths((String) views.value(fields, "career_paths", String.class));
    if (fields.containsKey("status"))
      entity.setStatus((String) views.value(fields, "status", String.class));
    if (fields.containsKey("source_revision_id"))
      entity.setSourceRevisionId((Long) views.value(fields, "source_revision_id", Long.class));
    if (fields.containsKey("reviewed_by"))
      entity.setReviewedBy((UUID) views.value(fields, "reviewed_by", UUID.class));
    if (fields.containsKey("published_at"))
      entity.setPublishedAt((Instant) views.value(fields, "published_at", Instant.class));
    if (fields.containsKey("updated_at"))
      entity.setUpdatedAt((Instant) views.value(fields, "updated_at", Instant.class));
    if (fields.containsKey("lock_version"))
      entity.setLockVersion((Long) views.value(fields, "lock_version", Long.class));
    return views.of(curriculumVersions.saveAndFlush(entity));
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> createGuideVersions(Map<String, Object> fields) {
    var entity = new GuideVersionsEntity();
    if (fields.containsKey("id")) entity.setId((Long) views.value(fields, "id", Long.class));
    if (fields.containsKey("guide_id"))
      entity.setGuideId((Long) views.value(fields, "guide_id", Long.class));
    if (fields.containsKey("version_no"))
      entity.setVersionNo((Integer) views.value(fields, "version_no", Integer.class));
    if (fields.containsKey("status"))
      entity.setStatus((String) views.value(fields, "status", String.class));
    if (fields.containsKey("title"))
      entity.setTitle((String) views.value(fields, "title", String.class));
    if (fields.containsKey("body"))
      entity.setBody((String) views.value(fields, "body", String.class));
    if (fields.containsKey("source_revision_id"))
      entity.setSourceRevisionId((Long) views.value(fields, "source_revision_id", Long.class));
    if (fields.containsKey("reviewed_by"))
      entity.setReviewedBy((UUID) views.value(fields, "reviewed_by", UUID.class));
    if (fields.containsKey("published_at"))
      entity.setPublishedAt((Instant) views.value(fields, "published_at", Instant.class));
    if (fields.containsKey("updated_at"))
      entity.setUpdatedAt((Instant) views.value(fields, "updated_at", Instant.class));
    if (fields.containsKey("lock_version"))
      entity.setLockVersion((Long) views.value(fields, "lock_version", Long.class));
    return views.of(guideVersions.saveAndFlush(entity));
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> createFinancialPolicyVersions(Map<String, Object> fields) {
    var entity = new FinancialPolicyVersionsEntity();
    if (fields.containsKey("id")) entity.setId((Long) views.value(fields, "id", Long.class));
    if (fields.containsKey("policy_id"))
      entity.setPolicyId((Long) views.value(fields, "policy_id", Long.class));
    if (fields.containsKey("version_no"))
      entity.setVersionNo((Integer) views.value(fields, "version_no", Integer.class));
    if (fields.containsKey("status"))
      entity.setStatus((String) views.value(fields, "status", String.class));
    if (fields.containsKey("title"))
      entity.setTitle((String) views.value(fields, "title", String.class));
    if (fields.containsKey("amount_vnd"))
      entity.setAmountVnd((Long) views.value(fields, "amount_vnd", Long.class));
    if (fields.containsKey("charge_unit"))
      entity.setChargeUnit((String) views.value(fields, "charge_unit", String.class));
    if (fields.containsKey("eligibility_text"))
      entity.setEligibilityText((String) views.value(fields, "eligibility_text", String.class));
    if (fields.containsKey("due_at"))
      entity.setDueAt((Instant) views.value(fields, "due_at", Instant.class));
    if (fields.containsKey("body"))
      entity.setBody((String) views.value(fields, "body", String.class));
    if (fields.containsKey("source_revision_id"))
      entity.setSourceRevisionId((Long) views.value(fields, "source_revision_id", Long.class));
    if (fields.containsKey("reviewed_by"))
      entity.setReviewedBy((UUID) views.value(fields, "reviewed_by", UUID.class));
    if (fields.containsKey("published_at"))
      entity.setPublishedAt((Instant) views.value(fields, "published_at", Instant.class));
    if (fields.containsKey("updated_at"))
      entity.setUpdatedAt((Instant) views.value(fields, "updated_at", Instant.class));
    if (fields.containsKey("lock_version"))
      entity.setLockVersion((Long) views.value(fields, "lock_version", Long.class));
    return views.of(financialPolicyVersions.saveAndFlush(entity));
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> createScoreRuleVersions(Map<String, Object> fields) {
    var entity = new ScoreRuleVersionsEntity();
    if (fields.containsKey("id")) entity.setId((Long) views.value(fields, "id", Long.class));
    if (fields.containsKey("score_rule_id"))
      entity.setScoreRuleId((Long) views.value(fields, "score_rule_id", Long.class));
    if (fields.containsKey("version_no"))
      entity.setVersionNo((Integer) views.value(fields, "version_no", Integer.class));
    if (fields.containsKey("status"))
      entity.setStatus((String) views.value(fields, "status", String.class));
    if (fields.containsKey("source_revision_id"))
      entity.setSourceRevisionId((Long) views.value(fields, "source_revision_id", Long.class));
    if (fields.containsKey("reviewed_by"))
      entity.setReviewedBy((UUID) views.value(fields, "reviewed_by", UUID.class));
    if (fields.containsKey("formula_key"))
      entity.setFormulaKey((String) views.value(fields, "formula_key", String.class));
    if (fields.containsKey("formula_config"))
      entity.setFormulaConfig(
          (Map<String, Object>) views.value(fields, "formula_config", Map.class));
    if (fields.containsKey("output_scale_code"))
      entity.setOutputScaleCode((String) views.value(fields, "output_scale_code", String.class));
    if (fields.containsKey("published_at"))
      entity.setPublishedAt((Instant) views.value(fields, "published_at", Instant.class));
    if (fields.containsKey("updated_at"))
      entity.setUpdatedAt((Instant) views.value(fields, "updated_at", Instant.class));
    if (fields.containsKey("lock_version"))
      entity.setLockVersion((Long) views.value(fields, "lock_version", Long.class));
    return views.of(scoreRuleVersions.saveAndFlush(entity));
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> createCycleMethods(Map<String, Object> fields) {
    var entity = new CycleMethodsEntity();
    if (fields.containsKey("id")) entity.setId((Long) views.value(fields, "id", Long.class));
    if (fields.containsKey("cycle_version_id"))
      entity.setCycleVersionId((Long) views.value(fields, "cycle_version_id", Long.class));
    if (fields.containsKey("method_id"))
      entity.setMethodId((Short) views.value(fields, "method_id", Short.class));
    if (fields.containsKey("eligibility_text"))
      entity.setEligibilityText((String) views.value(fields, "eligibility_text", String.class));
    if (fields.containsKey("source_revision_id"))
      entity.setSourceRevisionId((Long) views.value(fields, "source_revision_id", Long.class));
    return views.of(cycleMethods.saveAndFlush(entity));
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> createAdmissionChoices(Map<String, Object> fields) {
    var entity = new AdmissionChoicesEntity();
    if (fields.containsKey("id")) entity.setId((Long) views.value(fields, "id", Long.class));
    if (fields.containsKey("cycle_version_id"))
      entity.setCycleVersionId((Long) views.value(fields, "cycle_version_id", Long.class));
    if (fields.containsKey("program_offering_id"))
      entity.setProgramOfferingId((Long) views.value(fields, "program_offering_id", Long.class));
    if (fields.containsKey("total_quota"))
      entity.setTotalQuota((Integer) views.value(fields, "total_quota", Integer.class));
    if (fields.containsKey("source_revision_id"))
      entity.setSourceRevisionId((Long) views.value(fields, "source_revision_id", Long.class));
    return views.of(admissionChoices.saveAndFlush(entity));
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> createAdmissionOptions(Map<String, Object> fields) {
    var entity = new AdmissionOptionsEntity();
    if (fields.containsKey("id")) entity.setId((Long) views.value(fields, "id", Long.class));
    if (fields.containsKey("cycle_version_id"))
      entity.setCycleVersionId((Long) views.value(fields, "cycle_version_id", Long.class));
    if (fields.containsKey("choice_id"))
      entity.setChoiceId((Long) views.value(fields, "choice_id", Long.class));
    if (fields.containsKey("cycle_method_id"))
      entity.setCycleMethodId((Long) views.value(fields, "cycle_method_id", Long.class));
    if (fields.containsKey("method_quota"))
      entity.setMethodQuota((Integer) views.value(fields, "method_quota", Integer.class));
    if (fields.containsKey("additional_conditions"))
      entity.setAdditionalConditions(
          (String) views.value(fields, "additional_conditions", String.class));
    return views.of(admissionOptions.saveAndFlush(entity));
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> createAdmissionOptionCombinations(Map<String, Object> fields) {
    var entity = new AdmissionOptionCombinationsEntity();
    if (fields.containsKey("option_id"))
      entity.setOptionId((Long) views.value(fields, "option_id", Long.class));
    if (fields.containsKey("combination_id"))
      entity.setCombinationId((Short) views.value(fields, "combination_id", Short.class));
    if (fields.containsKey("conditions_text"))
      entity.setConditionsText((String) views.value(fields, "conditions_text", String.class));
    return views.of(admissionOptionCombinations.saveAndFlush(entity));
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> createAdmissionCutoffs(Map<String, Object> fields) {
    var entity = new AdmissionCutoffsEntity();
    if (fields.containsKey("id")) entity.setId((Long) views.value(fields, "id", Long.class));
    if (fields.containsKey("option_id"))
      entity.setOptionId((Long) views.value(fields, "option_id", Long.class));
    if (fields.containsKey("combination_id"))
      entity.setCombinationId((Short) views.value(fields, "combination_id", Short.class));
    if (fields.containsKey("score"))
      entity.setScore((BigDecimal) views.value(fields, "score", BigDecimal.class));
    if (fields.containsKey("scale_code"))
      entity.setScaleCode((String) views.value(fields, "scale_code", String.class));
    if (fields.containsKey("source_revision_id"))
      entity.setSourceRevisionId((Long) views.value(fields, "source_revision_id", Long.class));
    return views.of(admissionCutoffs.saveAndFlush(entity));
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> createCurriculumCourses(Map<String, Object> fields) {
    var entity = new CurriculumCoursesEntity();
    if (fields.containsKey("curriculum_version_id"))
      entity.setCurriculumVersionId(
          (Long) views.value(fields, "curriculum_version_id", Long.class));
    if (fields.containsKey("course_id"))
      entity.setCourseId((Long) views.value(fields, "course_id", Long.class));
    if (fields.containsKey("course_title"))
      entity.setCourseTitle((String) views.value(fields, "course_title", String.class));
    if (fields.containsKey("credits"))
      entity.setCredits((Short) views.value(fields, "credits", Short.class));
    if (fields.containsKey("course_group"))
      entity.setCourseGroup((String) views.value(fields, "course_group", String.class));
    if (fields.containsKey("suggested_term"))
      entity.setSuggestedTerm((Short) views.value(fields, "suggested_term", Short.class));
    return views.of(curriculumCourses.saveAndFlush(entity));
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> createGuideEntries(Map<String, Object> fields) {
    var entity = new GuideEntriesEntity();
    if (fields.containsKey("id")) entity.setId((Long) views.value(fields, "id", Long.class));
    if (fields.containsKey("guide_version_id"))
      entity.setGuideVersionId((Long) views.value(fields, "guide_version_id", Long.class));
    if (fields.containsKey("position"))
      entity.setPosition((Short) views.value(fields, "position", Short.class));
    if (fields.containsKey("entry_type"))
      entity.setEntryType((String) views.value(fields, "entry_type", String.class));
    if (fields.containsKey("label"))
      entity.setLabel((String) views.value(fields, "label", String.class));
    if (fields.containsKey("instructions"))
      entity.setInstructions((String) views.value(fields, "instructions", String.class));
    if (fields.containsKey("link_url"))
      entity.setLinkUrl((String) views.value(fields, "link_url", String.class));
    if (fields.containsKey("is_required"))
      entity.setIsRequired((Boolean) views.value(fields, "is_required", Boolean.class));
    return views.of(guideEntries.saveAndFlush(entity));
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> createScoreRuleInputs(Map<String, Object> fields) {
    var entity = new ScoreRuleInputsEntity();
    if (fields.containsKey("id")) entity.setId((Long) views.value(fields, "id", Long.class));
    if (fields.containsKey("rule_version_id"))
      entity.setRuleVersionId((Long) views.value(fields, "rule_version_id", Long.class));
    if (fields.containsKey("input_kind"))
      entity.setInputKind((String) views.value(fields, "input_kind", String.class));
    if (fields.containsKey("input_code"))
      entity.setInputCode((String) views.value(fields, "input_code", String.class));
    if (fields.containsKey("label"))
      entity.setLabel((String) views.value(fields, "label", String.class));
    if (fields.containsKey("is_required"))
      entity.setIsRequired((Boolean) views.value(fields, "is_required", Boolean.class));
    if (fields.containsKey("min_value"))
      entity.setMinValue((BigDecimal) views.value(fields, "min_value", BigDecimal.class));
    if (fields.containsKey("max_value"))
      entity.setMaxValue((BigDecimal) views.value(fields, "max_value", BigDecimal.class));
    return views.of(scoreRuleInputs.saveAndFlush(entity));
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> createScoreRuleBands(Map<String, Object> fields) {
    var entity = new ScoreRuleBandsEntity();
    if (fields.containsKey("id")) entity.setId((Long) views.value(fields, "id", Long.class));
    if (fields.containsKey("rule_input_id"))
      entity.setRuleInputId((Long) views.value(fields, "rule_input_id", Long.class));
    if (fields.containsKey("match_code"))
      entity.setMatchCode((String) views.value(fields, "match_code", String.class));
    if (fields.containsKey("lower_bound"))
      entity.setLowerBound((BigDecimal) views.value(fields, "lower_bound", BigDecimal.class));
    if (fields.containsKey("upper_bound"))
      entity.setUpperBound((BigDecimal) views.value(fields, "upper_bound", BigDecimal.class));
    if (fields.containsKey("converted_value"))
      entity.setConvertedValue(
          (BigDecimal) views.value(fields, "converted_value", BigDecimal.class));
    return views.of(scoreRuleBands.saveAndFlush(entity));
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> createScheduleEvents(Map<String, Object> fields) {
    var entity = new ScheduleEventsEntity();
    if (fields.containsKey("id")) entity.setId((Long) views.value(fields, "id", Long.class));
    if (fields.containsKey("cycle_version_id"))
      entity.setCycleVersionId((Long) views.value(fields, "cycle_version_id", Long.class));
    if (fields.containsKey("guide_version_id"))
      entity.setGuideVersionId((Long) views.value(fields, "guide_version_id", Long.class));
    if (fields.containsKey("title"))
      entity.setTitle((String) views.value(fields, "title", String.class));
    if (fields.containsKey("starts_at"))
      entity.setStartsAt((Instant) views.value(fields, "starts_at", Instant.class));
    if (fields.containsKey("ends_at"))
      entity.setEndsAt((Instant) views.value(fields, "ends_at", Instant.class));
    if (fields.containsKey("location"))
      entity.setLocation((String) views.value(fields, "location", String.class));
    if (fields.containsKey("notes"))
      entity.setNotes((String) views.value(fields, "notes", String.class));
    return views.of(scheduleEvents.saveAndFlush(entity));
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> createPrograms(Map<String, Object> fields) {
    var entity = new ProgramsEntity();
    if (fields.containsKey("id")) entity.setId((Long) views.value(fields, "id", Long.class));
    if (fields.containsKey("field_id"))
      entity.setFieldId((Short) views.value(fields, "field_id", Short.class));
    if (fields.containsKey("parent_program_id"))
      entity.setParentProgramId((Long) views.value(fields, "parent_program_id", Long.class));
    if (fields.containsKey("code"))
      entity.setCode((String) views.value(fields, "code", String.class));
    if (fields.containsKey("name"))
      entity.setName((String) views.value(fields, "name", String.class));
    return views.of(programs.saveAndFlush(entity));
  }
}
