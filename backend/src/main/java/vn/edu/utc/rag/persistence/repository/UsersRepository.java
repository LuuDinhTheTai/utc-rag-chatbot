package vn.edu.utc.rag.persistence.repository;

import jakarta.persistence.LockModeType;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import vn.edu.utc.rag.persistence.entity.*;

public interface UsersRepository extends JpaRepository<UsersEntity, UUID> {

  java.util.Optional<UsersEntity> findByEmail(String email);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  java.util.List<UsersEntity> findLockedById(java.util.UUID id);

  @Query(
      "select count(u) from UsersEntity u join RoleAssignmentsEntity r on r.userId=u.id where"
          + " u.status='ACTIVE' and r.roleCode='ADMIN' and r.campusId is null")
  Long countActiveAdministrators();
}
