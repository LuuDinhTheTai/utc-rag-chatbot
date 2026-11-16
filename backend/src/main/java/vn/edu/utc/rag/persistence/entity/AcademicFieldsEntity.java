package vn.edu.utc.rag.persistence.entity;

import jakarta.persistence.*;
import java.util.*;

@Entity
@Table(name = "academic_fields")
public class AcademicFieldsEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", columnDefinition = "smallint")
  private Short id;

  public Short getId() {
    return id;
  }

  public void setId(Short value) {
    this.id = value;
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
