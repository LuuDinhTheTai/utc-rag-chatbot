package vn.edu.utc.rag.service.scoring;

import jakarta.servlet.http.HttpServletRequest;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.utc.rag.exception.ApiException;
import vn.edu.utc.rag.persistence.repository.query.ScoreCalculationQueryRepository;
import vn.edu.utc.rag.security.RateLimits;
import vn.edu.utc.rag.validation.Input;

@Service
@Transactional()
public class ScoringService {

  private final RateLimits limits;

  public ScoringService(
      RateLimits limits,
      ScoreCalculationQueryRepository repository,
      vn.edu.utc.rag.mapper.common.EntityMapper views,
      vn.edu.utc.rag.persistence.repository.DocumentRevisionsRepository documentRevisionsJpa,
      vn.edu.utc.rag.persistence.repository.ScoreRuleInputsRepository scoreRuleInputsJpa,
      vn.edu.utc.rag.persistence.repository.ScoreRuleBandsRepository scoreRuleBandsJpa) {
    this.limits = limits;
    this.repository = repository;
    this.views = views;
    this.documentRevisionsJpa = documentRevisionsJpa;
    this.scoreRuleInputsJpa = scoreRuleInputsJpa;
    this.scoreRuleBandsJpa = scoreRuleBandsJpa;
  }

  @SuppressWarnings("unchecked")
  @Transactional
  public Object calculate(Map<String, Object> b, HttpServletRequest req) {
    limits.hit("score:" + req.getRemoteAddr(), 30, Duration.ofMinutes(1));
    Input.only(
        b,
        "rule_version_id",
        "admission_option_id",
        "combination_code",
        "compare_previous_year",
        "inputs");
    long version = Input.id(b.get("rule_version_id"));
    // Lock the source and version so revocation/publication cannot race a calculation.
    var rule = repository.findScoreRuleVersionsForCalculate(Map.of("id", version));
    if (!"PUBLISHED".equals(rule.get("status")))
      throw new ApiException(409, "RULE_VERSION_CHANGED", "Reload the current score form.");
    findDocumentRevisionsForCalculate(Map.of("id", Input.id(rule.get("source_revision_id"))));
    String formula = rule.get("formula_key").toString();
    if (!Set.of("SUM", "WEIGHTED_SUM", "SUM_BANDS").contains(formula))
      throw ApiException.invalid(
          "Unsupported formula_key. Only reviewed supported formulas can run.");
    var config = (Map<String, Object>) rule.get("formula_config");
    Object weightsRaw = config.getOrDefault("weights", Map.of());
    if (!(weightsRaw instanceof Map<?, ?> weights))
      throw ApiException.invalid("Invalid formula weights.");
    var definitions = findScoreRuleInputsForCalculate(Map.of("v", version));
    var inputs = Input.objects(b, "inputs");
    Map<String, Map<String, Object>> byCode = new HashMap<>();
    for (var i : inputs) {
      Input.only(i, "input_code", "numeric_value", "choice_code");
      String code = Input.text(i, "input_code");
      if (byCode.put(code, i) != null) throw ApiException.invalid("Duplicate input code.");
    }
    List<Map<String, Object>> parts = new ArrayList<>();
    BigDecimal total = BigDecimal.ZERO;
    for (var definition : definitions) {
      String code = definition.get("input_code").toString();
      var input = byCode.remove(code);
      if (input == null) {
        if (Boolean.TRUE.equals(definition.get("is_required")))
          throw ApiException.invalid("Missing input " + code + ".");
        continue;
      }
      boolean numeric = input.containsKey("numeric_value"),
          choice = input.containsKey("choice_code");
      if (numeric == choice)
        throw ApiException.invalid("Supply exactly one numeric_value or choice_code.");
      BigDecimal value = numeric ? Input.decimal(input.get("numeric_value")) : null;
      if (value != null) {
        if (definition.get("min_value") != null
                && value.compareTo(new BigDecimal(definition.get("min_value").toString())) < 0
            || definition.get("max_value") != null
                && value.compareTo(new BigDecimal(definition.get("max_value").toString())) > 0)
          throw ApiException.invalid("Input is out of range: " + code);
      }
      var bands = findScoreRuleBandsForCalculate(Map.of("id", Input.id(definition.get("id"))));
      BigDecimal converted = value;
      if (!bands.isEmpty() || choice || formula.equals("SUM_BANDS")) {
        var matches =
            bands.stream()
                .filter(
                    band ->
                        choice
                            ? Objects.equals(band.get("match_code"), input.get("choice_code"))
                            : band.get("match_code") == null
                                && (band.get("lower_bound") == null
                                    || value.compareTo(
                                            new BigDecimal(band.get("lower_bound").toString()))
                                        >= 0)
                                && (band.get("upper_bound") == null
                                    || value.compareTo(
                                            new BigDecimal(band.get("upper_bound").toString()))
                                        <= 0))
                .toList();
        if (matches.size() != 1)
          throw ApiException.invalid(
              "Input does not match exactly one reviewed conversion band: " + code);
        converted = new BigDecimal(matches.getFirst().get("converted_value").toString());
      }
      BigDecimal coefficient =
          formula.equals("WEIGHTED_SUM") ? Input.decimal(weights.get(code)) : BigDecimal.ONE;
      BigDecimal score = converted.multiply(coefficient);
      total = total.add(score);
      parts.add(
          Map.of(
              "kind",
              definition.get("input_kind"),
              "label",
              definition.get("label"),
              "score",
              score));
    }
    if (definitions.isEmpty() || !byCode.isEmpty())
      throw ApiException.invalid("Unknown input or empty rule.");
    if (config.get("offset") != null) total = total.add(Input.decimal(config.get("offset")));
    if (config.get("maximum") != null) total = total.min(Input.decimal(config.get("maximum")));
    if (config.get("minimum") != null) total = total.max(Input.decimal(config.get("minimum")));
    int scale = config.get("decimal_places") instanceof Integer i ? i : 2;
    if (scale < 0 || scale > 8) throw ApiException.invalid("Invalid rounding precision.");
    total = total.setScale(scale, RoundingMode.HALF_UP);
    Object comparison = null;
    if (Boolean.TRUE.equals(b.get("compare_previous_year"))) comparison = compare(b, rule, total);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("rule_version_id", Long.toString(version));
    result.put("total", total);
    result.put("scale_code", rule.get("output_scale_code"));
    result.put("parts", parts);
    result.put("comparison", comparison);
    result.put("source", rule.get("source"));
    result.put("disclaimer", "Kết quả chỉ để tham khảo, không bảo đảm trúng tuyển.");
    return result;
  }

  private Object compare(Map<String, Object> b, Map<String, Object> rule, BigDecimal total) {
    if (b.get("admission_option_id") == null)
      throw ApiException.invalid("admission_option_id is required for comparison.");
    var option =
        repository.findAdmissionOptionsForCompare(
            Map.of("id", Input.id(b.get("admission_option_id"))));
    if (!Objects.equals(option.get("campus_id"), rule.get("campus_id"))
        || !Objects.equals(option.get("admission_year"), rule.get("admission_year"))
        || !Objects.equals(option.get("method_id"), rule.get("method_id")))
      throw ApiException.invalid("Rule and admission option scopes do not match.");
    var args = new HashMap<String, Object>();
    args.put("campus", Input.id(option.get("campus_id")));
    args.put("year", ((Number) option.get("admission_year")).intValue() - 1);
    args.put("program", Input.id(option.get("program_id")));
    args.put("method", Input.id(option.get("method_id")));
    args.put("round", option.get("round_code"));
    args.put("scale", rule.get("output_scale_code"));
    args.put("combination", b.get("combination_code"));
    var cutoffs = repository.findAdmissionCutoffsForCompare(args);
    if (cutoffs.size() != 1)
      return Map.of("status", "NO_COMPARABLE_CUTOFF", "reason", "NO_UNIQUE_MATCHING_CUTOFF");
    var cutoff = new BigDecimal(cutoffs.getFirst().get("score").toString());
    return Map.of("status", "COMPARED", "cutoff", cutoff, "difference", total.subtract(cutoff));
  }

  private final ScoreCalculationQueryRepository repository;

  private Map<String, Object> findDocumentRevisionsForCalculate(Map<String, ?> parameters) {
    var result =
        documentRevisionsJpa.findAllByIdAndRevokedAtIsNullAndSourceVerifiedTrue(
            (Long) views.value(parameters, "id", Long.class));
    var rows = result.stream().map(e -> views.project(e, "id")).toList();
    if (rows.isEmpty()) throw vn.edu.utc.rag.exception.ApiException.missing();
    return rows.getFirst();
  }

  private final vn.edu.utc.rag.mapper.common.EntityMapper views;

  private final vn.edu.utc.rag.persistence.repository.DocumentRevisionsRepository
      documentRevisionsJpa;

  private List<Map<String, Object>> findScoreRuleInputsForCalculate(Map<String, ?> parameters) {
    var result =
        scoreRuleInputsJpa.findAllByRuleVersionIdOrderByIdAsc(
            (Long) views.value(parameters, "v", Long.class));
    var rows = result.stream().map(e -> views.project(e, "*")).toList();
    return rows;
  }

  private final vn.edu.utc.rag.persistence.repository.ScoreRuleInputsRepository scoreRuleInputsJpa;

  private List<Map<String, Object>> findScoreRuleBandsForCalculate(Map<String, ?> parameters) {
    var result =
        scoreRuleBandsJpa.findAllByRuleInputIdOrderByIdAsc(
            (Long) views.value(parameters, "id", Long.class));
    var rows = result.stream().map(e -> views.project(e, "*")).toList();
    return rows;
  }

  private final vn.edu.utc.rag.persistence.repository.ScoreRuleBandsRepository scoreRuleBandsJpa;
}
