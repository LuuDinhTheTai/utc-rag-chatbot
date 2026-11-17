package vn.edu.utc.rag.persistence.entity;

import jakarta.persistence.*;
import java.util.*;

@Entity
@Table(name = "chat_suggestions")
public class ChatSuggestionsEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", columnDefinition = "bigint")
  private Long id;

  public Long getId() {
    return id;
  }

  public void setId(Long value) {
    this.id = value;
  }

  @Column(name = "topic_code", columnDefinition = "text")
  private String topicCode;

  public String getTopicCode() {
    return topicCode;
  }

  public void setTopicCode(String value) {
    this.topicCode = value;
  }

  @Column(name = "question_text", columnDefinition = "text")
  private String questionText;

  public String getQuestionText() {
    return questionText;
  }

  public void setQuestionText(String value) {
    this.questionText = value;
  }

  @Column(name = "display_order", columnDefinition = "smallint")
  private Short displayOrder;

  public Short getDisplayOrder() {
    return displayOrder;
  }

  public void setDisplayOrder(Short value) {
    this.displayOrder = value;
  }

  @Column(name = "enabled", columnDefinition = "boolean")
  private Boolean enabled;

  public Boolean getEnabled() {
    return enabled;
  }

  public void setEnabled(Boolean value) {
    this.enabled = value;
  }
}
