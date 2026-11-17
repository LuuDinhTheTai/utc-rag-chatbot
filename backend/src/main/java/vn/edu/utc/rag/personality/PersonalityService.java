package vn.edu.utc.rag.personality;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.utc.rag.common.*;
import vn.edu.utc.rag.security.*;

@Service
public class PersonalityService {

  private final JsonCodec json;

  private final Access access;

  private final Pages pages;

  private final RateLimits limits;

  public PersonalityService(
      JsonCodec json,
      Access access,
      Pages pages,
      RateLimits limits,
      PersonalityDataRepository repository) {
    this.json = json;
    this.access = access;
    this.pages = pages;
    this.limits = limits;
    this.repository = repository;
  }

  public Object create(Map<String, Object> b, HttpServletRequest req) {
    Input.only(b, "questionnaire_version_id");
    long v = Input.id(b.get("questionnaire_version_id"));
    repository.findQuestionnaireVersionsForCreate(Map.of("id", v));
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
    var r = repository.findPersonalityAttemptsForCreate(args);
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
    repository.findPersonalityAttemptsForAnswers(Map.of("id", id));
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
      var valid = repository.findPersonalityQuestionsForAnswers(Map.of("q", q, "o", o, "v", v));
      if (valid.isEmpty())
        throw ApiException.invalid("Question or option does not belong to this attempt.");
    }
    repository.applyPersonalityAnswersForAnswers(Map.of("id", id));
    for (var answer : answers)
      repository.applyPersonalityAnswersForAnswers2(
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
    repository.findPersonalityAttemptsForComplete(Map.of("id", id));
    var attempt = own(id, req);
    if (!"IN_PROGRESS".equals(attempt.get("status")))
      throw ApiException.conflict("Attempt is already completed.");
    long v = Input.id(attempt.get("questionnaire_version_id"));
    var n = repository.findPersonalityQuestionsForComplete(Map.of("v", v));
    var answers = repository.findPersonalityAnswersForComplete(Map.of("id", id));
    if (answers.isEmpty() || Long.parseLong(n.get("n").toString()) != answers.size())
      throw ApiException.invalid("All questions must be answered before scoring.");
    var version = repository.findQuestionnaireVersionsForComplete(Map.of("v", v));
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
    var profiles = repository.findPersonalityProfilesForComplete(Map.of("v", v));
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
    repository.applyPersonalityAttemptsForComplete(
        Map.of("id", id, "p", Input.id(best.get("id")), "scores", json.encode(dimensions)));
    return result(id, req);
  }

  public Map<String, Object> result(UUID id, HttpServletRequest req) {
    var a = own(id, req);
    if (!"COMPLETED".equals(a.get("status")))
      throw ApiException.conflict("Result is not available yet.");
    var p =
        repository.findPersonalityProfilesForResult(Map.of("id", Input.id(a.get("profile_id"))));
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
    repository.applyPersonalityAttemptsForSave(Map.of("id", id));
  }

  public Object history(HttpServletRequest req) {
    return repository.findPageForHistory(Map.of("u", access.requireUser(req)), pages.request(req));
  }

  private final PersonalityDataRepository repository;
}
