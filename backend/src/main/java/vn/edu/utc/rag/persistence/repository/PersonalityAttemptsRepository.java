package vn.edu.utc.rag.persistence.repository;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.edu.utc.rag.persistence.entity.*;

public interface PersonalityAttemptsRepository
    extends JpaRepository<PersonalityAttemptsEntity, UUID> {}
