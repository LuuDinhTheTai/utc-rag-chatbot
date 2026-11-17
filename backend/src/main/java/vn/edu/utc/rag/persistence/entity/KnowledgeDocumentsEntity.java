package vn.edu.utc.rag.persistence.entity;

import jakarta.persistence.*;
import java.util.*;

@Entity
@Table(name = "knowledge_documents")
public class KnowledgeDocumentsEntity {

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

  @Column(name = "canonical_url", columnDefinition = "text")
  private String canonicalUrl;

  public String getCanonicalUrl() {
    return canonicalUrl;
  }

  public void setCanonicalUrl(String value) {
    this.canonicalUrl = value;
  }

  @Column(name = "crawl_source_id", columnDefinition = "bigint")
  private Long crawlSourceId;

  public Long getCrawlSourceId() {
    return crawlSourceId;
  }

  public void setCrawlSourceId(Long value) {
    this.crawlSourceId = value;
  }

  @Column(name = "publication_version", columnDefinition = "bigint")
  private Long publicationVersion = 0L;

  public Long getPublicationVersion() {
    return publicationVersion;
  }

  public void setPublicationVersion(Long value) {
    this.publicationVersion = value;
  }
}
