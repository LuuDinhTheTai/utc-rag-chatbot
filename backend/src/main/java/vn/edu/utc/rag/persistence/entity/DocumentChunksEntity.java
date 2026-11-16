package vn.edu.utc.rag.persistence.entity;

import jakarta.persistence.*;
import java.util.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "document_chunks")
public class DocumentChunksEntity {
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

  @Column(name = "revision_id", columnDefinition = "bigint")
  private Long revisionId;

  public Long getRevisionId() {
    return revisionId;
  }

  public void setRevisionId(Long value) {
    this.revisionId = value;
  }

  @Column(name = "position", columnDefinition = "integer")
  private Integer position;

  public Integer getPosition() {
    return position;
  }

  public void setPosition(Integer value) {
    this.position = value;
  }

  @Column(name = "body", columnDefinition = "text")
  private String body;

  public String getBody() {
    return body;
  }

  public void setBody(String value) {
    this.body = value;
  }

  @Column(name = "locator", columnDefinition = "text")
  private String locator;

  public String getLocator() {
    return locator;
  }

  public void setLocator(String value) {
    this.locator = value;
  }

  @JdbcTypeCode(SqlTypes.VECTOR)
  @org.hibernate.annotations.Array(length = 768)
  @Column(name = "embedding", columnDefinition = "vector(768)")
  private float[] embedding;

  public float[] getEmbedding() {
    return embedding;
  }

  public void setEmbedding(float[] value) {
    this.embedding = value;
  }
}
