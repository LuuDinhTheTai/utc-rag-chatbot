package vn.edu.utc.rag.persistence.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.edu.utc.rag.persistence.entity.*;

public interface RoleAssignmentsRepository extends JpaRepository<RoleAssignmentsEntity, Long> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  java.util.List<RoleAssignmentsEntity> findLockedById(Long id);

  java.util.List<RoleAssignmentsEntity> findAllByUserId(java.util.UUID id);

  public record RoleView(Long id, String roleCode, String campusCode) {}

  @Query(
      "select new"
          + " vn.edu.utc.rag.persistence.repository.RoleAssignmentsRepository$RoleView(r.id,r.roleCode,c.code)"
          + " from RoleAssignmentsEntity r left join CampusesEntity c on c.id=r.campusId where"
          + " r.userId=:userId order by r.id")
  java.util.List<RoleView> findRolesForUser(@Param("userId") java.util.UUID userId);

  record RoleDetails(
      Long id,
      java.util.UUID userId,
      String roleCode,
      String campusCode,
      java.time.Instant assignedAt) {}

  @Query(
      "select new"
          + " vn.edu.utc.rag.persistence.repository.RoleAssignmentsRepository$RoleDetails(r.id,r.userId,r.roleCode,c.code,r.assignedAt)"
          + " from RoleAssignmentsEntity r left join CampusesEntity c on c.id=r.campusId where"
          + " r.id=:id")
  java.util.Optional<RoleDetails> findDetailsById(@Param("id") Long id);
}
