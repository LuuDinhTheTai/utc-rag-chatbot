package vn.edu.utc.rag.persistence.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.*;

@Entity
@Table(name = "profile_program_recommendations")
@IdClass(ProfileProgramRecommendationsEntityId.class)
public class ProfileProgramRecommendationsEntity {

  @Id
  @Column(name = "profile_id", columnDefinition = "bigint")
  private Long profileId;

  public Long getProfileId() {
    return profileId;
  }

  public void setProfileId(Long value) {
    this.profileId = value;
  }

  @Id
  @Column(name = "program_id", columnDefinition = "bigint")
  private Long programId;

  public Long getProgramId() {
    return programId;
  }

  public void setProgramId(Long value) {
    this.programId = value;
  }

  @Column(name = "rank_score", columnDefinition = "numeric")
  private BigDecimal rankScore;

  public BigDecimal getRankScore() {
    return rankScore;
  }

  public void setRankScore(BigDecimal value) {
    this.rankScore = value;
  }

  @Column(name = "rationale", columnDefinition = "text")
  private String rationale;

  public String getRationale() {
    return rationale;
  }

  public void setRationale(String value) {
    this.rationale = value;
  }
}
