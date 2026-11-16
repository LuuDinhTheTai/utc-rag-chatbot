package vn.edu.utc.rag.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "messages")
public class MessagesEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", columnDefinition = "uuid")
  private UUID id;

  public UUID getId() {
    return id;
  }

  public void setId(UUID value) {
    this.id = value;
  }

  @Column(name = "conversation_id", columnDefinition = "uuid")
  private UUID conversationId;

  public UUID getConversationId() {
    return conversationId;
  }

  public void setConversationId(UUID value) {
    this.conversationId = value;
  }

  @Column(name = "position", columnDefinition = "integer")
  private Integer position;

  public Integer getPosition() {
    return position;
  }

  public void setPosition(Integer value) {
    this.position = value;
  }

  @Column(name = "author_type", columnDefinition = "text")
  private String authorType;

  public String getAuthorType() {
    return authorType;
  }

  public void setAuthorType(String value) {
    this.authorType = value;
  }

  @Column(name = "body", columnDefinition = "text")
  private String body;

  public String getBody() {
    return body;
  }

  public void setBody(String value) {
    this.body = value;
  }

  @Column(name = "created_at", columnDefinition = "timestamptz")
  private Instant createdAt;

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant value) {
    this.createdAt = value;
  }

  @Column(name = "answer_status", columnDefinition = "text")
  private String answerStatus;

  public String getAnswerStatus() {
    return answerStatus;
  }

  public void setAnswerStatus(String value) {
    this.answerStatus = value;
  }

  @Column(name = "reply_to_id", columnDefinition = "uuid")
  private UUID replyToId;

  public UUID getReplyToId() {
    return replyToId;
  }

  public void setReplyToId(UUID value) {
    this.replyToId = value;
  }
}
