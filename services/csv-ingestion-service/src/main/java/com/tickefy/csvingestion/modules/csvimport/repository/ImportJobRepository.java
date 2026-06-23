package com.tickefy.csvingestion.modules.csvimport.repository;

import com.tickefy.csvingestion.modules.csvimport.entity.ImportJobEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportJobRepository extends JpaRepository<ImportJobEntity, UUID> {}
