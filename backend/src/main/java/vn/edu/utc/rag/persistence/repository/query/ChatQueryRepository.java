package vn.edu.utc.rag.persistence.repository.query;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.utc.rag.persistence.internal.NativeSqlExecutor;

@Repository
@Transactional
public class ChatQueryRepository {

  private final NativeSqlExecutor query;

  public ChatQueryRepository(
      NativeSqlExecutor query, vn.edu.utc.rag.persistence.query.QueryPages queryPages) {
    this.query = query;
    this.queryPages = queryPages;
  }

  public List<Map<String, Object>> findDocumentChunksForSend(Map<String, ?> parameters) {
    return query.list(
        "SELECT ch.id,ch.revision_id,left(ch.body,3000) AS"
            + " body,ch.locator,r.title,r.official_url,r.admission_year FROM document_chunks"
            + " ch JOIN document_revisions r ON r.id=ch.revision_id WHERE"
            + " r.status='PUBLISHED' AND r.source_verified AND r.revoked_at IS NULL AND"
            + " (ch.embedding <=> CAST(:v AS vector))<0.6 ORDER BY ch.embedding <=> CAST(:v"
            + " AS vector),ch.id LIMIT 3",
        parameters);
  }

  public int applyChatFeedbackEntriesForFeedback(Map<String, ?> parameters) {
    return query.update(
        "INSERT INTO chat_feedback_entries(answer_id,message_id,is_helpful,comment_text,created_at)"
            + " VALUES(:id,:id,:helpful,:comment,now()) ON CONFLICT(answer_id) DO UPDATE SET"
            + " is_helpful=excluded.is_helpful,comment_text=excluded.comment_text",
        parameters);
  }

  private final vn.edu.utc.rag.persistence.query.QueryPages queryPages;

  public Map<String, Object> findPageForHistory(
      Map<String, ?> input, vn.edu.utc.rag.persistence.query.QueryPage req) {
    Map<String, Object> parameters = new HashMap<>(input);
    String filter = "";
    if (req.getParameter("q") != null) {
      filter =
          " AND (display_title ILIKE :q OR EXISTS(SELECT 1 FROM messages m WHERE"
              + " m.conversation_id=c.id AND m.body ILIKE :q))";
      parameters.put("q", "%" + req.getParameter("q") + "%");
    }
    return queryPages.page(
        "SELECT id,display_title,created_at,updated_at FROM conversations c WHERE owner_user_id=:u"
            + filter,
        parameters,
        req,
        "updated_at:timestamptz:desc",
        "id:uuid:desc");
  }

  public Map<String, Object> findPageForMessages(
      Map<String, ?> input, vn.edu.utc.rag.persistence.query.QueryPage req) {
    Map<String, Object> parameters = new HashMap<>(input);
    return queryPages.page(
        "SELECT id,position FROM messages WHERE conversation_id=:id",
        parameters,
        req,
        "position:integer:asc",
        "id:uuid:asc");
  }
}
