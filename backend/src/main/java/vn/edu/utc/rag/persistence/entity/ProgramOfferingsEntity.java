package vn.edu.utc.rag.persistence.entity;

import jakarta.persistence.*;
import java.util.*;

@Entity
@Table(name = "program_offerings")
public class ProgramOfferingsEntity {

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

  @Column(name = "program_id", columnDefinition = "bigint")
  private Long programId;

  public Long getProgramId() {
    return programId;
  }

  public void setProgramId(Long value) {
    this.programId = value;
  }

  @Column(name = "campus_id", columnDefinition = "smallint")
  private Short campusId;

  public Short getCampusId() {
    return campusId;
  }

  public void setCampusId(Short value) {
    this.campusId = value;
  }

  @Column(name = "entry_year", columnDefinition = "smallint")
  private Short entryYear;

  public Short getEntryYear() {
    return entryYear;
  }

  public void setEntryYear(Short value) {
    this.entryYear = value;
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
