package vn.edu.utc.rag.persistence.entity;

import jakarta.persistence.*;
import java.util.*;

@Entity
@Table(name = "programs")
public class ProgramsEntity {

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

  @Column(name = "field_id", columnDefinition = "smallint")
  private Short fieldId;

  public Short getFieldId() {
    return fieldId;
  }

  public void setFieldId(Short value) {
    this.fieldId = value;
  }

  @Column(name = "parent_program_id", columnDefinition = "bigint")
  private Long parentProgramId;

  public Long getParentProgramId() {
    return parentProgramId;
  }

  public void setParentProgramId(Long value) {
    this.parentProgramId = value;
  }

  @Column(name = "code", columnDefinition = "text")
  private String code;

  public String getCode() {
    return code;
  }

  public void setCode(String value) {
    this.code = value;
  }

  @Column(name = "name", columnDefinition = "text")
  private String name;

  public String getName() {
    return name;
  }

  public void setName(String value) {
    this.name = value;
  }
}
