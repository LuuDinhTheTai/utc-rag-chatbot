package vn.edu.utc.rag.persistence.entity;

import jakarta.persistence.*;
import java.util.*;

@Entity
@Table(name = "financial_policies")
public class FinancialPoliciesEntity {

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

  @Column(name = "program_offering_id", columnDefinition = "bigint")
  private Long programOfferingId;

  public Long getProgramOfferingId() {
    return programOfferingId;
  }

  public void setProgramOfferingId(Long value) {
    this.programOfferingId = value;
  }

  @Column(name = "policy_type", columnDefinition = "text")
  private String policyType;

  public String getPolicyType() {
    return policyType;
  }

  public void setPolicyType(String value) {
    this.policyType = value;
  }

  @Column(name = "policy_code", columnDefinition = "text")
  private String policyCode;

  public String getPolicyCode() {
    return policyCode;
  }

  public void setPolicyCode(String value) {
    this.policyCode = value;
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
