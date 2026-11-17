package vn.edu.utc.rag.scoring;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.utc.rag.persistence.internal.NativeSqlExecutor;

@Repository
@Transactional
public class ScoringDataRepository {

  private final NativeSqlExecutor query;

  public ScoringDataRepository(
      NativeSqlExecutor query,
      vn.edu.utc.rag.persistence.internal.EntityViews views,
      vn.edu.utc.rag.persistence.repository.DocumentRevisionsRepository documentRevisionsJpa,
      vn.edu.utc.rag.persistence.repository.ScoreRuleBandsRepository scoreRuleBandsJpa,
      vn.edu.utc.rag.persistence.repository.ScoreRuleInputsRepository scoreRuleInputsJpa) {
    this.query = query;
    this.views = views;
    this.documentRevisionsJpa = documentRevisionsJpa;
    this.scoreRuleBandsJpa = scoreRuleBandsJpa;
    this.scoreRuleInputsJpa = scoreRuleInputsJpa;
  }

  public Map<String, Object> findScoreRuleVersionsForCalculate(Map<String, ?> parameters) {
    return query.one(
        "SELECT v.*,r.campus_id,r.admission_year,r.method_id,s.source FROM score_rule_versions"
            + " v JOIN score_rules r ON r.id=v.score_rule_id JOIN api_verified_sources s ON"
            + " s.id=v.source_revision_id WHERE v.id=:id FOR SHARE OF v",
        parameters);
  }

  public Map<String, Object> findDocumentRevisionsForCalculate(Map<String, ?> parameters) {
    var result =
        documentRevisionsJpa.findAllByIdAndRevokedAtIsNullAndSourceVerifiedTrue(
            (Long) views.value(parameters, "id", Long.class));
    var rows = result.stream().map(e -> views.project(e, "id")).toList();
    if (rows.isEmpty()) throw vn.edu.utc.rag.common.ApiException.missing();
    return rows.getFirst();
  }

  public List<Map<String, Object>> findScoreRuleInputsForCalculate(Map<String, ?> parameters) {
    var result =
        scoreRuleInputsJpa.findAllByRuleVersionIdOrderByIdAsc(
            (Long) views.value(parameters, "v", Long.class));
    var rows = result.stream().map(e -> views.project(e, "*")).toList();
    return rows;
  }

  public List<Map<String, Object>> findScoreRuleBandsForCalculate(Map<String, ?> parameters) {
    var result =
        scoreRuleBandsJpa.findAllByRuleInputIdOrderByIdAsc(
            (Long) views.value(parameters, "id", Long.class));
    var rows = result.stream().map(e -> views.project(e, "*")).toList();
    return rows;
  }

  public Map<String, Object> findAdmissionOptionsForCompare(Map<String, ?> parameters) {
    return query.one(
        "SELECT o.id,po.program_id,c.campus_id,c.admission_year,m.method_id,c.round_code FROM"
            + " admission_options o JOIN admission_choices ch ON ch.id=o.choice_id JOIN"
            + " program_offerings po ON po.id=ch.program_offering_id JOIN api_admission_cycles"
            + " c ON c.version_id=o.cycle_version_id JOIN cycle_methods m ON"
            + " m.id=o.cycle_method_id WHERE o.id=:id",
        parameters);
  }

  public List<Map<String, Object>> findAdmissionCutoffsForCompare(Map<String, ?> parameters) {
    return query.list(
        "SELECT cutoff.score FROM admission_cutoffs cutoff JOIN admission_options o ON"
            + " o.id=cutoff.option_id JOIN admission_choices ch ON ch.id=o.choice_id JOIN"
            + " program_offerings po ON po.id=ch.program_offering_id JOIN api_admission_cycles"
            + " c ON c.version_id=o.cycle_version_id JOIN cycle_methods m ON"
            + " m.id=o.cycle_method_id JOIN api_verified_sources s ON"
            + " s.id=cutoff.source_revision_id LEFT JOIN subject_combinations comb ON"
            + " comb.id=cutoff.combination_id WHERE c.campus_id=:campus AND"
            + " c.admission_year=:year AND po.program_id=:program AND m.method_id=:method AND"
            + " c.round_code=:round AND cutoff.scale_code=:scale AND comb.code IS NOT DISTINCT"
            + " FROM :combination",
        parameters);
  }

  private final vn.edu.utc.rag.persistence.internal.EntityViews views;

  private final vn.edu.utc.rag.persistence.repository.DocumentRevisionsRepository
      documentRevisionsJpa;

  private final vn.edu.utc.rag.persistence.repository.ScoreRuleBandsRepository scoreRuleBandsJpa;

  private final vn.edu.utc.rag.persistence.repository.ScoreRuleInputsRepository scoreRuleInputsJpa;
}
