package com.tickefy.order.modules.order.service;

import com.tickefy.order.modules.order.dto.RefundJobResponse;
import com.tickefy.order.modules.order.entity.RefundJobEntity;
import com.tickefy.order.modules.order.mapper.RefundJobMapper;
import com.tickefy.order.modules.order.repository.RefundJobRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefundAdminService {

    private static final String ENABLED = "ENABLED";

    private final RefundJobRepository refundJobRepository;
    private final RefundJobMapper refundJobMapper;

    public RefundAdminService(
            RefundJobRepository refundJobRepository,
            RefundJobMapper refundJobMapper) {
        this.refundJobRepository = refundJobRepository;
        this.refundJobMapper = refundJobMapper;
    }

    /** Short DB-only transaction. Existing jobs are returned unchanged for idempotent replay. */
    @Transactional
    public RefundJobResponse enableRefund(UUID concertId) {
        return refundJobRepository.findByConcertId(concertId)
                .map(refundJobMapper::toResponse)
                .orElseGet(() -> refundJobMapper.toResponse(refundJobRepository.save(
                        RefundJobEntity.builder()
                                .concertId(concertId)
                                .enabledAt(Instant.now())
                                .status(ENABLED)
                                .build())));
    }
}
