package vn.edu.utc.rag.persistence.entity;

import jakarta.persistence.*;
import java.util.*;

@Entity
@Table(name = "message_citations")
@IdClass(MessageCitationsEntityId.class)
public class MessageCitationsEntity {

  @Id
  @Column(name = "message_id", columnDefinition = "uuid")
  private UUID messageId;

  public UUID getMessageId() {
    return messageId;
  }

  public void setMessageId(UUID value) {
    this.messageId = value;
  }

  @Id
  @Column(name = "position", columnDefinition = "smallint")
  private Short position;

  public Short getPosition() {
    return position;
  }

  public void setPosition(Short value) {
    this.position = value;
  }

  @Column(name = "chunk_id", columnDefinition = "bigint")
  private Long chunkId;

  public Long getChunkId() {
    return chunkId;
  }

  public void setChunkId(Long value) {
    this.chunkId = value;
  }

  @Column(name = "source_title", columnDefinition = "text")
  private String sourceTitle;

  public String getSourceTitle() {
    return sourceTitle;
  }

  public void setSourceTitle(String value) {
    this.sourceTitle = value;
  }

  @Column(name = "source_url", columnDefinition = "text")
  private String sourceUrl;

  public String getSourceUrl() {
    return sourceUrl;
  }

  public void setSourceUrl(String value) {
    this.sourceUrl = value;
  }

  @Column(name = "source_year", columnDefinition = "smallint")
  private Short sourceYear;

  public Short getSourceYear() {
    return sourceYear;
  }

  public void setSourceYear(Short value) {
    this.sourceYear = value;
  }

  @Column(name = "locator", columnDefinition = "text")
  private String locator;

  public String getLocator() {
    return locator;
  }

  public void setLocator(String value) {
    this.locator = value;
  }
}
