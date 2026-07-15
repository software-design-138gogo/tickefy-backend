package com.tickefy.checkin.modules.vip.repository;

import com.tickefy.checkin.modules.vip.entity.VipCacheMetaEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VipCacheMetaRepository extends JpaRepository<VipCacheMetaEntity, UUID> {}
