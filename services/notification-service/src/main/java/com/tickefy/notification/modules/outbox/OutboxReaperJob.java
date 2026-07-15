package com.tickefy.notification.modules.outbox;

import com.tickefy.notification.modules.core.repository.NotificationOutboxRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "tickefy.outbox.reaper.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxReaperJob {

    private final NotificationOutboxRepository outboxRepository;

    /**
     * Chạy mỗi 5 phút để dọn dẹp các record bị kẹt ở trạng thái PROCESSING quá lâu.
     * Nguyên nhân có thể do Worker nhận message nhưng bị crash/OOM trước khi cập
     * nhật trạng thái.
     */
    @Scheduled(fixedDelay = 300000) // 5 minutes
    @Transactional
    public void resetStuckRecords() {
        // Nếu record kẹt ở PROCESSING quá 5 phút, trả về PENDING để gửi lại
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(5);
        int resetCount = outboxRepository.resetStuckProcessingRecords(threshold);
        if (resetCount > 0) {
            log.warn("[OutboxReaper] Found and reset {} stuck PROCESSING records back to PENDING.", resetCount);
        }
    }

    /**
     * Chạy mỗi đêm lúc 3:00 sáng để xóa bớt các record đã gửi thành công (SENT) từ
     * lâu,
     * giúp tránh làm phình to bảng outbox.
     */
    @Scheduled(cron = "0 0 3 * * *") // 3:00 AM every day
    @Transactional
    public void cleanupOldRecords() {
        // Xóa các record SENT đã quá 30 ngày
        LocalDateTime threshold = LocalDateTime.now().minusDays(30);
        int deletedCount = outboxRepository.deleteOldSentRecords(threshold);
        if (deletedCount > 0) {
            log.info("[OutboxReaper] Cleaned up {} old SENT records.", deletedCount);
        }
    }
}
