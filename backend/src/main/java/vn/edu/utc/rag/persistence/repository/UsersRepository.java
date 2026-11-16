package vn.edu.utc.rag.persistence.repository;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.edu.utc.rag.persistence.entity.*;

public interface UsersRepository extends JpaRepository<UsersEntity, UUID> {
  java.util.Optional<UsersEntity> findByEmail(String email);
}
