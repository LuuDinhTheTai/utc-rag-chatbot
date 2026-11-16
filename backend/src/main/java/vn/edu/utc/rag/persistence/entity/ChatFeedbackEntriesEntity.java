package vn.edu.utc.rag.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "chat_feedback_entries")
public class ChatFeedbackEntriesEntity {
  @Id
  @Column(name = "answer_id", columnDefinition = "uuid")
  private UUID answerId;

  public UUID getAnswerId() {
    return answerId;
  }

  public void setAnswerId(UUID value) {
    this.answerId = value;
  }

  @Column(name = "message_id", columnDefinition = "uuid")
  private UUID messageId;

  public UUID getMessageId() {
    return messageId;
  }

  public void setMessageId(UUID value) {
    this.messageId = value;
  }

  @Column(name = "is_helpful", columnDefinition = "boolean")
  private Boolean isHelpful;

  public Boolean getIsHelpful() {
    return isHelpful;
  }

  public void setIsHelpful(Boolean value) {
    this.isHelpful = value;
  }

  @Column(name = "comment_text", columnDefinition = "text")
  private String commentText;

  public String getCommentText() {
    return commentText;
  }

  public void setCommentText(String value) {
    this.commentText = value;
  }

  @Column(name = "created_at", columnDefinition = "timestamptz")
  private Instant createdAt;

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant value) {
    this.createdAt = value;
  }
}
