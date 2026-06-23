package com.tickefy.payment.modules.payment.service;

import com.tickefy.payment.modules.payment.entity.PaymentTransaction;
import com.tickefy.payment.modules.payment.gateway.PaymentGatewayClient;
import com.tickefy.payment.modules.payment.gateway.SePayClient.QueryStatusResult;
import com.tickefy.payment.modules.payment.repository.PaymentTransactionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reconciliation job: quét PENDING stale → queryStatus gateway → resolve SUCCESS/FAILED.
 * Bean RIÊNG (KHÔNG self-invoke): @Scheduled ở đây, resolve TX ngắn qua proxy bean paymentTxService.
 * queryStatus NGOÀI TX (CB bọc trong PaymentGatewayClient).
 */
@Component
@ConditionalOnProperty(name = "app.payment.reconcile.enabled", havingValue = "true", matchIfMissing = true)
public class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

    private final PaymentTransactionRepository txRepo;
    private final PaymentGatewayClient paymentGatewayClient;
    private final PaymentTxService paymentTxService;

    @Value("${app.payment.reconcile.stale-after:PT10M}")
    private Duration staleAfter;

    @Value("${app.payment.reconcile.batch-size:50}")
    private int batchSize;

    public ReconciliationJob(
            PaymentTransactionRepository txRepo,
            PaymentGatewayClient paymentGatewayClient,
            PaymentTxService paymentTxService) {
        this.txRepo = txRepo;
        this.paymentGatewayClient = paymentGatewayClient;
        this.paymentTxService = paymentTxService;
    }

    @Scheduled(fixedDelayString = "${app.payment.reconcile.delay:PT5M}")
    public void reconcile() {
        Instant cutoff = Instant.now().minus(staleAfter);
        List<PaymentTransaction> stale =
                txRepo.findByStatusAndCreatedAtBefore("PENDING", cutoff, PageRequest.of(0, batchSize));

        if (stale.isEmpty()) {
            log.debug("recon: no stale PENDING transactions (cutoff={})", cutoff);
            return;
        }

        log.info("recon: found {} stale PENDING transactions (cutoff={})", stale.size(), cutoff);

        for (PaymentTransaction tx : stale) {
            try {
                // [CHỐT §F.2] khóa = gateway_order_id (luôn có sau PENDING/TX2)
                String key = tx.getGatewayOrderId();
                // queryStatus NGOÀI TX — CB bọc trong PaymentGatewayClient
                QueryStatusResult r = paymentGatewayClient.queryStatus(key);

                switch (r.status()) {
                    case "SUCCESS" ->
                        // resolve TX ngắn qua proxy bean (cross-bean)
                        paymentTxService.resolveSuccess(tx.getId(), r.gatewayTransactionId());
                    case "FAILED" ->
                        // [CHỐT §F.1] reason = "PAYMENT_FAILED"
                        paymentTxService.resolveFailed(tx.getId(), "PAYMENT_FAILED");
                    default ->
                        log.debug("recon skip still-PENDING tx={} status={}", tx.getId(), r.status());
                }
            } catch (Exception e) {
                // CB-open / gateway-fail → skip tx, giữ PENDING, retry cycle sau
                log.warn("recon skip tx={} err={}", tx.getId(), e.toString());
            }
        }
    }
}
