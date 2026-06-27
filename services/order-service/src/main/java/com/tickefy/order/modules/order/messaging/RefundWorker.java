package com.tickefy.order.modules.order.messaging;

import com.tickefy.order.modules.order.service.RefundProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.refund.worker.enabled", havingValue = "true", matchIfMissing = true)
public class RefundWorker {

    private final RefundProcessor refundProcessor;

    public RefundWorker(RefundProcessor refundProcessor) {
        this.refundProcessor = refundProcessor;
    }

    @Scheduled(fixedDelayString = "${app.refund.worker.fixed-delay-ms:60000}")
    public void processRefunds() {
        refundProcessor.processRefunds();
    }
}
