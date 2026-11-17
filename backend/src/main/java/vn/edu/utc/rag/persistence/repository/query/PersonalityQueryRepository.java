package vn.edu.utc.rag.persistence.repository.query;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional
public class PersonalityQueryRepository {

  public PersonalityQueryRepository(vn.edu.utc.rag.persistence.query.QueryPages queryPages) {
    this.queryPages = queryPages;
  }

  private final vn.edu.utc.rag.persistence.query.QueryPages queryPages;

  public Map<String, Object> findPageForRecommendations(
      Map<String, ?> input, vn.edu.utc.rag.persistence.query.QueryPage req) {
    Map<String, Object> parameters = new HashMap<>(input);
    return queryPages.page(
        "SELECT p.id,p.code,p.name,r.rank_score,r.rationale FROM profile_program_recommendations r"
            + " JOIN ("
            + vn.edu.utc.rag.persistence.repository.query.CatalogQueryRepository.PROGRAMS
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
}
