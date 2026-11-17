package vn.edu.utc.rag.service.personality;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.utc.rag.common.json.JsonCodec;
import vn.edu.utc.rag.exception.ApiException;
import vn.edu.utc.rag.persistence.repository.query.PersonalityQueryRepository;
import vn.edu.utc.rag.security.*;
import vn.edu.utc.rag.service.catalog.CatalogService;
import vn.edu.utc.rag.validation.Input;
import vn.edu.utc.rag.web.pagination.PageRequestFactory;

@Service
@Transactional()
public class PersonalityService {

  private final JsonCodec json;

  private final Access access;

  private final PageRequestFactory pages;

  private final RateLimits limits;

  public PersonalityService(
      JsonCodec json,
      Access access,
      PageRequestFactory pages,
      RateLimits limits,
      PersonalityQueryRepository repository,
      vn.edu.utc.rag.mapper.common.EntityMapper views,
      vn.edu.utc.rag.persistence.repository.QuestionnaireVersionsRepository
          questionnaireVersionsJpa,
      vn.edu.utc.rag.persistence.repository.PersonalityAttemptsRepository personalityAttemptsJpa,
      vn.edu.utc.rag.persistence.repository.PersonalityQuestionsRepository personalityQuestionsJpa,
      vn.edu.utc.rag.persistence.repository.PersonalityAnswersRepository personalityAnswersJpa,
      vn.edu.utc.rag.persistence.repository.PersonalityOptionsRepository personalityOptionsJpa,
      vn.edu.utc.rag.persistence.repository.PersonalityProfilesRepository personalityProfilesJpa,
      CatalogService catalog) {
    this.json = json;
    this.access = access;
    this.pages = pages;
    this.limits = limits;
    this.repository = repository;
    this.views = views;
    this.questionnaireVersionsJpa = questionnaireVersionsJpa;
    this.personalityAttemptsJpa = personalityAttemptsJpa;
    this.personalityQuestionsJpa = personalityQuestionsJpa;
    this.personalityAnswersJpa = personalityAnswersJpa;
    this.personalityOptionsJpa = personalityOptionsJpa;
    this.personalityProfilesJpa = personalityProfilesJpa;
    this.catalog = catalog;
  }

  public Object create(Map<String, Object> b, HttpServletRequest req) {
    Input.only(b, "questionnaire_version_id");
    long v = Input.id(b.get("questionnaire_version_id"));
    findQuestionnaireVersionsForCreate(Map.of("id", v));
    UUID u = access.user(req);
    var g = access.guest(req, false);
    if (u == null && g == null)
      throw new ApiException(401, "UNAUTHENTICATED", "Initialize a guest session first.");
    limits.hit("attempt:" + access.caller(req), 10, Duration.ofMinutes(1));
    var args = new HashMap<String, Object>();
    args.put("v", v);
    args.put("u", u);
    args.put("g", u == null ? g.hash() : null);
    args.put("expires", u == null ? OffsetDateTime.ofInstant(g.expires(), ZoneOffset.UTC) : null);
    var r = findPersonalityAttemptsForCreate(args);
    return clean(r);
  }

  public Map<String, Object> clean(Map<String, Object> r) {
    r.remove("guest_token_hash");
    r.remove("owner_user_id");
    return r;
  }

  public Map<String, Object> own(UUID id, HttpServletRequest req) {
    return access.own("personality_attempts", id, req);
  }

  @Transactional
  public void answers(UUID id, Map<String, Object> b, HttpServletRequest req) {
    findPersonalityAttemptsForAnswers(Map.of("id", id));
    var attempt = own(id, req);
    if (!"IN_PROGRESS".equals(attempt.get("status")))
      throw ApiException.conflict("Attempt is already completed.");
    Input.only(b, "answers");
    var answers = Input.objects(b, "answers");
    long v = Input.id(attempt.get("questionnaire_version_id"));
    Set<Long> seen = new HashSet<>();
    for (var answer : answers) {
      Input.only(answer, "question_id", "option_id");
      long q = Input.id(answer.get("question_id")), o = Input.id(answer.get("option_id"));
      if (!seen.add(q)) throw ApiException.invalid("Duplicate answer.");
      var valid = findPersonalityQuestionsForAnswers(Map.of("q", q, "o", o, "v", v));
      if (valid.isEmpty())
        throw ApiException.invalid("Question or option does not belong to this attempt.");
    }
    applyPersonalityAnswersForAnswers(Map.of("id", id));
    for (var answer : answers)
      applyPersonalityAnswersForAnswers2(
          Map.of(
              "id",
              id,
              "q",
              Input.id(answer.get("question_id")),
              "o",
              Input.id(answer.get("option_id")),
              "v",
              v));
  }

  @SuppressWarnings("unchecked")
  @Transactional
  public Object complete(UUID id, HttpServletRequest req) {
    findPersonalityAttemptsForComplete(Map.of("id", id));
    var attempt = own(id, req);
    if (!"IN_PROGRESS".equals(attempt.get("status")))
      throw ApiException.conflict("Attempt is already completed.");
    long v = Input.id(attempt.get("questionnaire_version_id"));
    var n = findPersonalityQuestionsForComplete(Map.of("v", v));
    var answers = findPersonalityAnswersForComplete(Map.of("id", id));
    if (answers.isEmpty() || Long.parseLong(n.get("n").toString()) != answers.size())
      throw ApiException.invalid("All questions must be answered before scoring.");
    var version = findQuestionnaireVersionsForComplete(Map.of("v", v));
    var config = (Map<String, Object>) version.get("scoring_config");
    if (!"WEIGHTED_SUM".equals(config.get("strategy"))
        || !(config.get("profiles") instanceof Map<?, ?> profileConfig))
      throw ApiException.invalid("Unsupported or missing scoring configuration.");
    Map<String, BigDecimal> dimensions = new TreeMap<>();
    for (var answer : answers) {
      var weights = (Map<String, Object>) answer.get("score_weights");
      weights.forEach(
          (dimension, weight) ->
              dimensions.merge(dimension, Input.decimal(weight), BigDecimal::add));
    }
    var profiles = findPersonalityProfilesForComplete(Map.of("v", v));
    Map<String, Object> best = null;
    BigDecimal bestScore = null;
    for (var profile : profiles) {
      Object raw = profileConfig.get(profile.get("profile_code"));
      if (!(raw instanceof Map<?, ?> weights))
        throw ApiException.invalid("Missing profile weights.");
      BigDecimal score = BigDecimal.ZERO;
      for (var weight : weights.entrySet())
        score =
            score.add(
                dimensions
                    .getOrDefault(weight.getKey().toString(), BigDecimal.ZERO)
                    .multiply(Input.decimal(weight.getValue())));
      if (bestScore == null || score.compareTo(bestScore) > 0) {
        best = profile;
        bestScore = score;
      }
    }
    if (best == null) throw ApiException.invalid("No personality profiles configured.");
    applyPersonalityAttemptsForComplete(
        Map.of("id", id, "p", Input.id(best.get("id")), "scores", json.encode(dimensions)));
    return result(id, req);
  }

  public Map<String, Object> result(UUID id, HttpServletRequest req) {
    var a = own(id, req);
    if (!"COMPLETED".equals(a.get("status")))
      throw ApiException.conflict("Result is not available yet.");
    var p = findPersonalityProfilesForResult(Map.of("id", Input.id(a.get("profile_id"))));
    return Map.of(
        "attempt_id",
        id,
        "questionnaire_version_id",
        a.get("questionnaire_version_id"),
        "profile",
        p,
        "scores",
        a.get("score_summary"),
        "completed_at",
        a.get("completed_at"),
        "disclaimer",
        "Kết quả tham khảo, không phải chẩn đoán hoặc bảo đảm lựa chọn ngành.");
  }

  public Object recommendations(UUID id, HttpServletRequest req) {
    var a = own(id, req);
    result(id, req);
    return repository.findPageForRecommendations(
        Map.of("id", Input.id(a.get("profile_id"))), pages.request(req));
  }

  @Transactional
  public void save(UUID id, HttpServletRequest req) {
    UUID u = access.requireUser(req);
    var a = own(id, req);
    if (!u.toString().equals(a.get("owner_user_id"))) throw ApiException.missing();
    result(id, req);
    applyPersonalityAttemptsForSave(Map.of("id", id));
  }

  public Object history(HttpServletRequest req) {
    return repository.findPageForHistory(Map.of("u", access.requireUser(req)), pages.request(req));
  }

  private final PersonalityQueryRepository repository;

  private Map<String, Object> findQuestionnaireVersionsForCreate(Map<String, ?> parameters) {
    var result =
        questionnaireVersionsJpa.findPublishedById(
            (Long) views.value(parameters, "id", Long.class));
    var rows = result.stream().map(e -> views.project(e, "id")).toList();
    if (rows.isEmpty()) throw vn.edu.utc.rag.exception.ApiException.missing();
    return rows.getFirst();
  }

  private final vn.edu.utc.rag.mapper.common.EntityMapper views;

  private final vn.edu.utc.rag.persistence.repository.QuestionnaireVersionsRepository
      questionnaireVersionsJpa;

  private Map<String, Object> findPersonalityAttemptsForCreate(Map<String, ?> parameters) {
    var entity = new vn.edu.utc.rag.persistence.entity.PersonalityAttemptsEntity();
    entity.setQuestionnaireVersionId((Long) views.value(parameters, "v", Long.class));
    entity.setOwnerUserId((UUID) views.value(parameters, "u", UUID.class));
    entity.setGuestTokenHash((String) views.value(parameters, "g", String.class));
    entity.setStatus("IN_PROGRESS");
    entity.setCreatedAt(java.time.Instant.now());
    entity.setExpiresAt((Instant) views.value(parameters, "expires", Instant.class));
    var saved = personalityAttemptsJpa.saveAndFlush(entity);
    return views.project(saved, "*");
  }

  private final vn.edu.utc.rag.persistence.repository.PersonalityAttemptsRepository
      personalityAttemptsJpa;

  private Map<String, Object> findPersonalityAttemptsForAnswers(Map<String, ?> parameters) {
    var result =
        personalityAttemptsJpa.findLockedById(
            (java.util.UUID) views.value(parameters, "id", java.util.UUID.class));
    var rows = result.stream().map(e -> views.project(e, "id")).toList();
    if (rows.isEmpty()) throw vn.edu.utc.rag.exception.ApiException.missing();
    return rows.getFirst();
  }

  private List<Map<String, Object>> findPersonalityQuestionsForAnswers(Map<String, ?> parameters) {
    return personalityQuestionsJpa
        .findMatchingOption(
            ((Number) parameters.get("q")).longValue(),
            ((Number) parameters.get("o")).longValue(),
            ((Number) parameters.get("v")).longValue())
        .stream()
        .map(e -> views.project(e, "id"))
        .toList();
  }

  private final vn.edu.utc.rag.persistence.repository.PersonalityQuestionsRepository
      personalityQuestionsJpa;

  private int applyPersonalityAnswersForAnswers(Map<String, ?> parameters) {
    {
      int count =
          personalityAnswersJpa.deleteByAttemptId(
              (java.util.UUID) views.value(parameters, "id", java.util.UUID.class));
      personalityAnswersJpa.flush();
      return count;
    }
  }

  private final vn.edu.utc.rag.persistence.repository.PersonalityAnswersRepository
      personalityAnswersJpa;

  private int applyPersonalityAnswersForAnswers2(Map<String, ?> parameters) {
    var entity = new vn.edu.utc.rag.persistence.entity.PersonalityAnswersEntity();
    entity.setAttemptId((UUID) views.value(parameters, "id", UUID.class));
    entity.setQuestionId((Long) views.value(parameters, "q", Long.class));
    entity.setOptionId((Long) views.value(parameters, "o", Long.class));
    entity.setQuestionnaireVersionId((Long) views.value(parameters, "v", Long.class));
    var saved = personalityAnswersJpa.saveAndFlush(entity);
    return 1;
  }

  private Map<String, Object> findPersonalityAttemptsForComplete(Map<String, ?> parameters) {
    var result =
        personalityAttemptsJpa.findLockedById((UUID) views.value(parameters, "id", UUID.class));
    var rows = result.stream().map(e -> views.project(e, "id")).toList();
    if (rows.isEmpty()) throw vn.edu.utc.rag.exception.ApiException.missing();
    return rows.getFirst();
  }

  private Map<String, Object> findPersonalityQuestionsForComplete(Map<String, ?> parameters) {
    var result =
        personalityQuestionsJpa.countByQuestionnaireVersionId(
            (Long) views.value(parameters, "v", Long.class));
    return java.util.Map.of("n", result.toString());
  }

  private List<Map<String, Object>> findPersonalityAnswersForComplete(Map<String, ?> parameters) {
    return personalityOptionsJpa.findAnsweredOptions((UUID) parameters.get("id")).stream()
        .map(e -> views.project(e, "score_weights"))
        .toList();
  }

  private final vn.edu.utc.rag.persistence.repository.PersonalityOptionsRepository
      personalityOptionsJpa;

  private Map<String, Object> findQuestionnaireVersionsForComplete(Map<String, ?> parameters) {
    var result = questionnaireVersionsJpa.findById((Long) views.value(parameters, "v", Long.class));
    var rows = result.stream().map(e -> views.project(e, "scoring_config")).toList();
    if (rows.isEmpty()) throw vn.edu.utc.rag.exception.ApiException.missing();
    return rows.getFirst();
  }

  private List<Map<String, Object>> findPersonalityProfilesForComplete(Map<String, ?> parameters) {
    var result =
        personalityProfilesJpa.findAllByQuestionnaireVersionIdOrderByProfileCodeAsc(
            (Long) views.value(parameters, "v", Long.class));
    var rows = result.stream().map(e -> views.project(e, "*")).toList();
    return rows;
  }

  private final vn.edu.utc.rag.persistence.repository.PersonalityProfilesRepository
      personalityProfilesJpa;

  private int applyPersonalityAttemptsForComplete(Map<String, ?> parameters) {
    var e =
        personalityAttemptsJpa
            .findById((UUID) parameters.get("id"))
            .orElseThrow(vn.edu.utc.rag.exception.ApiException::missing);
    e.setStatus("COMPLETED");
    e.setProfileId(((Number) parameters.get("p")).longValue());
    e.setScoreSummary((Map<String, Object>) views.value(parameters, "scores", Map.class));
    e.setCompletedAt(java.time.Instant.now());
    return 1;
  }

  private Map<String, Object> findPersonalityProfilesForResult(Map<String, ?> parameters) {
    var result = personalityProfilesJpa.findById((Long) views.value(parameters, "id", Long.class));
    var rows =
        result.stream().map(e -> views.project(e, "id,profile_code,name,description")).toList();
    if (rows.isEmpty()) throw vn.edu.utc.rag.exception.ApiException.missing();
    return rows.getFirst();
  }

  private int applyPersonalityAttemptsForSave(Map<String, ?> parameters) {
    var e =
        personalityAttemptsJpa
            .findById((UUID) parameters.get("id"))
            .orElseThrow(vn.edu.utc.rag.exception.ApiException::missing);
    if (e.getSavedAt() == null) e.setSavedAt(java.time.Instant.now());
    return 1;
  }

  private final CatalogService catalog;

  public Object questions(UUID id, HttpServletRequest req) {
    var attempt = own(id, req);
    return catalog.questionsForVersion(Input.id(attempt.get("questionnaire_version_id")), req);
  }

  public Object get(UUID id, HttpServletRequest req) {
    return clean(own(id, req));
  }
}
