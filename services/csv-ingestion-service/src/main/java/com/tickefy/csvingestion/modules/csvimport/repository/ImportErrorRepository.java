package com.tickefy.csvingestion.modules.csvimport.repository;

import com.tickefy.csvingestion.modules.csvimport.entity.ImportErrorEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportErrorRepository extends JpaRepository<ImportErrorEntity, UUID> {

    List<ImportErrorEntity> findByImportJobId(UUID importJobId);
}
