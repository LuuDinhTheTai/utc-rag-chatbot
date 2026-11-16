package vn.edu.utc.rag.personality;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.utc.rag.catalog.CatalogController;
import vn.edu.utc.rag.common.*;
import vn.edu.utc.rag.security.*;

@Service
public class PersonalityService {
  private final Db db;
  private final Access access;
  private final Pages pages;
  private final RateLimits limits;

  public PersonalityService(Db db, Access access, Pages pages, RateLimits limits) {
    this.db = db;
    this.access = access;
    this.pages = pages;
    this.limits = limits;
  }

  public Object create(Map<String, Object> b, HttpServletRequest req) {
    Input.only(b, "questionnaire_version_id");
    long v = Input.id(b.get("questionnaire_version_id"));
    db.one(
        "SELECT id FROM questionnaire_versions WHERE id=:id AND status='PUBLISHED'",
        Map.of("id", v));
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
    var r =
        db.one(
            "INSERT INTO"
                + " personality_attempts(questionnaire_version_id,owner_user_id,guest_token_hash,status,created_at,expires_at)"
                + " VALUES(:v,:u,:g,'IN_PROGRESS',now(),:expires) RETURNING *",
            args);
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
    db.one("SELECT id FROM personality_attempts WHERE id=:id FOR UPDATE", Map.of("id", id));
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
      var valid =
          db.list(
              "SELECT q.id FROM personality_questions q JOIN personality_options o ON"
                  + " o.question_id=q.id WHERE q.id=:q AND o.id=:o AND"
                  + " q.questionnaire_version_id=:v",
              Map.of("q", q, "o", o, "v", v));
      if (valid.isEmpty())
        throw ApiException.invalid("Question or option does not belong to this attempt.");
    }
    db.update("DELETE FROM personality_answers WHERE attempt_id=:id", Map.of("id", id));
    for (var answer : answers)
      db.update(
          "INSERT INTO"
              + " personality_answers(attempt_id,question_id,option_id,questionnaire_version_id)"
              + " VALUES(:id,:q,:o,:v)",
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
    db.one("SELECT id FROM personality_attempts WHERE id=:id FOR UPDATE", Map.of("id", id));
    var attempt = own(id, req);
    if (!"IN_PROGRESS".equals(attempt.get("status")))
      throw ApiException.conflict("Attempt is already completed.");
    long v = Input.id(attempt.get("questionnaire_version_id"));
    var n =
        db.one(
            "SELECT count(*) AS n FROM personality_questions WHERE questionnaire_version_id=:v",
            Map.of("v", v));
    var answers =
        db.list(
            "SELECT o.score_weights FROM personality_answers a JOIN personality_options o ON"
                + " o.id=a.option_id WHERE attempt_id=:id",
            Map.of("id", id));
    if (answers.isEmpty() || Long.parseLong(n.get("n").toString()) != answers.size())
      throw ApiException.invalid("All questions must be answered before scoring.");
    var version =
        db.one("SELECT scoring_config FROM questionnaire_versions WHERE id=:v", Map.of("v", v));
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
    var profiles =
        db.list(
            "SELECT * FROM personality_profiles WHERE questionnaire_version_id=:v ORDER BY"
                + " profile_code",
            Map.of("v", v));
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
    db.update(
        "UPDATE personality_attempts SET"
            + " status='COMPLETED',profile_id=:p,score_summary=CAST(:scores AS"
            + " jsonb),completed_at=now() WHERE id=:id",
        Map.of("id", id, "p", Input.id(best.get("id")), "scores", db.encode(dimensions)));
    return result(id, req);
  }

  public Map<String, Object> result(UUID id, HttpServletRequest req) {
    var a = own(id, req);
    if (!"COMPLETED".equals(a.get("status")))
      throw ApiException.conflict("Result is not available yet.");
    var p =
        db.one(
            "SELECT id,profile_code,name,description FROM personality_profiles WHERE id=:id",
            Map.of("id", Input.id(a.get("profile_id"))));
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
    return pages.page(
        "SELECT p.id,p.code,p.name,r.rank_score,r.rationale FROM profile_program_recommendations r"
            + " JOIN ("
            + CatalogController.PROGRAMS
            + ") p ON p.id=r.program_id WHERE r.profile_id=:id",
        Map.of("id", Input.id(a.get("profile_id"))),
        req,
        "rank_score:numeric:desc",
        "id:bigint:asc");
  }

  @Transactional
  public void save(UUID id, HttpServletRequest req) {
    UUID u = access.requireUser(req);
    var a = own(id, req);
    if (!u.toString().equals(a.get("owner_user_id"))) throw ApiException.missing();
    result(id, req);
    db.update(
        "UPDATE personality_attempts SET saved_at=coalesce(saved_at,now()) WHERE id=:id",
        Map.of("id", id));
  }

  public Object history(HttpServletRequest req) {
    return pages.page(
        "SELECT"
            + " id,questionnaire_version_id,profile_id,score_summary,created_at,completed_at,saved_at"
            + " FROM personality_attempts WHERE owner_user_id=:u AND saved_at IS NOT NULL",
        Map.of("u", access.requireUser(req)),
        req,
        "saved_at:timestamptz:desc",
        "id:uuid:desc");
  }
}
