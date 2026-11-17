package vn.edu.utc.rag.personality;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional
public class PersonalityDataRepository {

  public PersonalityDataRepository(
      vn.edu.utc.rag.persistence.internal.EntityViews views,
      vn.edu.utc.rag.persistence.repository.PersonalityAnswersRepository personalityAnswersJpa,
      vn.edu.utc.rag.persistence.repository.PersonalityAttemptsRepository personalityAttemptsJpa,
      vn.edu.utc.rag.persistence.repository.PersonalityProfilesRepository personalityProfilesJpa,
      vn.edu.utc.rag.persistence.repository.PersonalityQuestionsRepository personalityQuestionsJpa,
      vn.edu.utc.rag.persistence.repository.QuestionnaireVersionsRepository
          questionnaireVersionsJpa,
      vn.edu.utc.rag.persistence.query.QueryPages queryPages,
      vn.edu.utc.rag.persistence.repository.PersonalityOptionsRepository personalityOptionsJpa) {
    this.views = views;
    this.personalityAnswersJpa = personalityAnswersJpa;
    this.personalityAttemptsJpa = personalityAttemptsJpa;
    this.personalityProfilesJpa = personalityProfilesJpa;
    this.personalityQuestionsJpa = personalityQuestionsJpa;
    this.questionnaireVersionsJpa = questionnaireVersionsJpa;
    this.queryPages = queryPages;
    this.personalityOptionsJpa = personalityOptionsJpa;
  }

  public Map<String, Object> findQuestionnaireVersionsForCreate(Map<String, ?> parameters) {
    var result =
        questionnaireVersionsJpa.findPublishedById(
            (Long) views.value(parameters, "id", Long.class));
    var rows = result.stream().map(e -> views.project(e, "id")).toList();
    if (rows.isEmpty()) throw vn.edu.utc.rag.common.ApiException.missing();
    return rows.getFirst();
  }

  public Map<String, Object> findPersonalityAttemptsForCreate(Map<String, ?> parameters) {
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

  public Map<String, Object> findPersonalityAttemptsForAnswers(Map<String, ?> parameters) {
    var result =
        personalityAttemptsJpa.findLockedById(
            (java.util.UUID) views.value(parameters, "id", java.util.UUID.class));
    var rows = result.stream().map(e -> views.project(e, "id")).toList();
    if (rows.isEmpty()) throw vn.edu.utc.rag.common.ApiException.missing();
    return rows.getFirst();
  }

  public List<Map<String, Object>> findPersonalityQuestionsForAnswers(Map<String, ?> parameters) {
    return personalityQuestionsJpa
        .findMatchingOption(
            ((Number) parameters.get("q")).longValue(),
            ((Number) parameters.get("o")).longValue(),
            ((Number) parameters.get("v")).longValue())
        .stream()
        .map(e -> views.project(e, "id"))
        .toList();
  }

  public int applyPersonalityAnswersForAnswers(Map<String, ?> parameters) {
    {
      int count =
          personalityAnswersJpa.deleteByAttemptId(
              (java.util.UUID) views.value(parameters, "id", java.util.UUID.class));
      personalityAnswersJpa.flush();
      return count;
    }
  }

  public int applyPersonalityAnswersForAnswers2(Map<String, ?> parameters) {
    var entity = new vn.edu.utc.rag.persistence.entity.PersonalityAnswersEntity();
    entity.setAttemptId((UUID) views.value(parameters, "id", UUID.class));
    entity.setQuestionId((Long) views.value(parameters, "q", Long.class));
    entity.setOptionId((Long) views.value(parameters, "o", Long.class));
    entity.setQuestionnaireVersionId((Long) views.value(parameters, "v", Long.class));
    var saved = personalityAnswersJpa.saveAndFlush(entity);
    return 1;
  }

  public Map<String, Object> findPersonalityAttemptsForComplete(Map<String, ?> parameters) {
    var result =
        personalityAttemptsJpa.findLockedById((UUID) views.value(parameters, "id", UUID.class));
    var rows = result.stream().map(e -> views.project(e, "id")).toList();
    if (rows.isEmpty()) throw vn.edu.utc.rag.common.ApiException.missing();
    return rows.getFirst();
  }

  public Map<String, Object> findPersonalityQuestionsForComplete(Map<String, ?> parameters) {
    var result =
        personalityQuestionsJpa.countByQuestionnaireVersionId(
            (Long) views.value(parameters, "v", Long.class));
    return java.util.Map.of("n", result.toString());
  }

  public List<Map<String, Object>> findPersonalityAnswersForComplete(Map<String, ?> parameters) {
    return personalityOptionsJpa.findAnsweredOptions((UUID) parameters.get("id")).stream()
        .map(e -> views.project(e, "score_weights"))
        .toList();
  }

  public Map<String, Object> findQuestionnaireVersionsForComplete(Map<String, ?> parameters) {
    var result = questionnaireVersionsJpa.findById((Long) views.value(parameters, "v", Long.class));
    var rows = result.stream().map(e -> views.project(e, "scoring_config")).toList();
    if (rows.isEmpty()) throw vn.edu.utc.rag.common.ApiException.missing();
    return rows.getFirst();
  }

  public List<Map<String, Object>> findPersonalityProfilesForComplete(Map<String, ?> parameters) {
    var result =
        personalityProfilesJpa.findAllByQuestionnaireVersionIdOrderByProfileCodeAsc(
            (Long) views.value(parameters, "v", Long.class));
    var rows = result.stream().map(e -> views.project(e, "*")).toList();
    return rows;
  }

  public int applyPersonalityAttemptsForComplete(Map<String, ?> parameters) {
    var e =
        personalityAttemptsJpa
            .findById((UUID) parameters.get("id"))
            .orElseThrow(vn.edu.utc.rag.common.ApiException::missing);
    e.setStatus("COMPLETED");
    e.setProfileId(((Number) parameters.get("p")).longValue());
    e.setScoreSummary((Map<String, Object>) views.value(parameters, "scores", Map.class));
    e.setCompletedAt(java.time.Instant.now());
    return 1;
  }

  public Map<String, Object> findPersonalityProfilesForResult(Map<String, ?> parameters) {
    var result = personalityProfilesJpa.findById((Long) views.value(parameters, "id", Long.class));
    var rows =
        result.stream().map(e -> views.project(e, "id,profile_code,name,description")).toList();
    if (rows.isEmpty()) throw vn.edu.utc.rag.common.ApiException.missing();
    return rows.getFirst();
  }

  public int applyPersonalityAttemptsForSave(Map<String, ?> parameters) {
    var e =
        personalityAttemptsJpa
            .findById((UUID) parameters.get("id"))
            .orElseThrow(vn.edu.utc.rag.common.ApiException::missing);
    if (e.getSavedAt() == null) e.setSavedAt(java.time.Instant.now());
    return 1;
  }

  private final vn.edu.utc.rag.persistence.internal.EntityViews views;

  private final vn.edu.utc.rag.persistence.repository.PersonalityAnswersRepository
      personalityAnswersJpa;

  private final vn.edu.utc.rag.persistence.repository.PersonalityAttemptsRepository
      personalityAttemptsJpa;

  private final vn.edu.utc.rag.persistence.repository.PersonalityProfilesRepository
      personalityProfilesJpa;

  private final vn.edu.utc.rag.persistence.repository.PersonalityQuestionsRepository
      personalityQuestionsJpa;

  private final vn.edu.utc.rag.persistence.repository.QuestionnaireVersionsRepository
      questionnaireVersionsJpa;

  private final vn.edu.utc.rag.persistence.query.QueryPages queryPages;

  public Map<String, Object> findPageForRecommendations(
      Map<String, ?> input, vn.edu.utc.rag.persistence.query.QueryPage req) {
    Map<String, Object> parameters = new HashMap<>(input);
    return queryPages.page(
        "SELECT p.id,p.code,p.name,r.rank_score,r.rationale FROM profile_program_recommendations r"
            + " JOIN ("
            + vn.edu.utc.rag.catalog.CatalogReadRepository.PROGRAMS
            + ") p ON p.id=r.program_id WHERE r.profile_id=:id",
        parameters,
        req,
        "rank_score:numeric:desc",
        "id:bigint:asc");
  }

  public Map<String, Object> findPageForHistory(
      Map<String, ?> input, vn.edu.utc.rag.persistence.query.QueryPage req) {
    Map<String, Object> parameters = new HashMap<>(input);
    return queryPages.page(
        "SELECT"
            + " id,questionnaire_version_id,profile_id,score_summary,created_at,completed_at,saved_at"
            + " FROM personality_attempts WHERE owner_user_id=:u AND saved_at IS NOT NULL",
        parameters,
        req,
        "saved_at:timestamptz:desc",
        "id:uuid:desc");
  }

  private final vn.edu.utc.rag.persistence.repository.PersonalityOptionsRepository
      personalityOptionsJpa;
}
