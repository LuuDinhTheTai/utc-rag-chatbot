package vn.edu.utc.rag.persistence.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.edu.utc.rag.persistence.entity.*;

public interface CampusesRepository extends JpaRepository<CampusesEntity, Short> {

  java.util.List<CampusesEntity> findAllByCode(String code);
}
