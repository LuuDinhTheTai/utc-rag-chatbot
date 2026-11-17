package vn.edu.utc.rag.catalog;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.utc.rag.persistence.internal.NativeSqlExecutor;

@Repository
@Transactional
public class CatalogDataRepository {

  private final NativeSqlExecutor query;

  public CatalogDataRepository(
      NativeSqlExecutor query,
      vn.edu.utc.rag.persistence.internal.EntityViews views,
      vn.edu.utc.rag.persistence.repository.QuestionnaireVersionsRepository
          questionnaireVersionsJpa) {
    this.query = query;
    this.views = views;
    this.questionnaireVersionsJpa = questionnaireVersionsJpa;
  }

  public Map<String, Object> findRecordsForOne(Map<String, ?> parameters, String sql) {
    return query.one(sql, parameters);
  }

  public Map<String, Object> findQuestionnaireVersionsForQuestions(Map<String, ?> parameters) {
    var result =
        questionnaireVersionsJpa.findPublishedByIdAndQuestionnaireId(
            (Long) views.value(parameters, "v", Long.class),
            (Long) views.value(parameters, "q", Long.class));
    var rows = result.stream().map(e -> views.project(e, "id")).toList();
    if (rows.isEmpty()) throw vn.edu.utc.rag.common.ApiException.missing();
    return rows.getFirst();
  }

  private final vn.edu.utc.rag.persistence.internal.EntityViews views;

  private final vn.edu.utc.rag.persistence.repository.QuestionnaireVersionsRepository
      questionnaireVersionsJpa;
}
