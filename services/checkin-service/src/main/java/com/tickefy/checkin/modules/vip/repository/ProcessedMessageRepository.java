package com.tickefy.checkin.modules.vip.repository;

import com.tickefy.checkin.modules.vip.entity.ProcessedMessageEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedMessageRepository extends JpaRepository<ProcessedMessageEntity, UUID> {}
