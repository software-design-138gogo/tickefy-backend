package com.tickefy.order.modules.order.mapper;

import com.tickefy.order.modules.order.dto.RefundJobResponse;
import com.tickefy.order.modules.order.entity.RefundJobEntity;
import org.springframework.stereotype.Component;

@Component
public class RefundJobMapper {

    public RefundJobResponse toResponse(RefundJobEntity refundJob) {
        return new RefundJobResponse(
                refundJob.getConcertId(),
                refundJob.getStatus(),
                refundJob.getEnabledAt());
    }
}
