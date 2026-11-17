package vn.edu.utc.rag.persistence.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.edu.utc.rag.persistence.entity.*;

public interface AdmissionOptionsRepository extends JpaRepository<AdmissionOptionsEntity, Long> {

  int deleteByCycleVersionId(Long v);

  record OptionWithMethod(AdmissionOptionsEntity option, Short methodId) {}

  @Query(
      "select new"
          + " vn.edu.utc.rag.persistence.repository.AdmissionOptionsRepository$OptionWithMethod(o,m.methodId)"
          + " from AdmissionOptionsEntity o join CycleMethodsEntity m on m.id=o.cycleMethodId where"
          + " o.choiceId=:choice order by o.id")
  java.util.List<OptionWithMethod> findOptionsWithMethod(@Param("choice") Long choice);
}
