package vn.edu.utc.rag.persistence.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.edu.utc.rag.persistence.entity.*;

public interface ScoreRuleBandsRepository extends JpaRepository<ScoreRuleBandsEntity, Long> {

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "delete from ScoreRuleBandsEntity e where e.ruleInputId in (select i.id from"
          + " ScoreRuleInputsEntity i where i.ruleVersionId=:version)")
  int deleteByRuleVersion(@Param("version") Long version);

  java.util.List<ScoreRuleBandsEntity> findAllByRuleInputIdOrderByIdAsc(Long id);

  java.util.List<ScoreRuleBandsEntity> findAllByRuleInputIdAndMatchCodeIsNotNullOrderByIdAsc(
      Long id);
}
