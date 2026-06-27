package com.tickefy.order.modules.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tickefy.order.modules.order.dto.RefundJobResponse;
import com.tickefy.order.modules.order.entity.RefundJobEntity;
import com.tickefy.order.modules.order.mapper.RefundJobMapper;
import com.tickefy.order.modules.order.repository.RefundJobRepository;
import com.tickefy.order.modules.order.service.RefundAdminService;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class RefundAdminServiceUnitTest {

    @Mock
    private RefundJobRepository refundJobRepository;

    private final RefundJobMapper refundJobMapper = new RefundJobMapper();

    @Test
    void enableRefund_whenMissing_savesEnabledJob() {
        UUID concertId = UUID.randomUUID();
        when(refundJobRepository.findByConcertId(concertId)).thenReturn(Optional.empty());
        when(refundJobRepository.save(any(RefundJobEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        RefundJobResponse response = service().enableRefund(concertId);

        ArgumentCaptor<RefundJobEntity> captor = ArgumentCaptor.forClass(RefundJobEntity.class);
        verify(refundJobRepository).save(captor.capture());
        RefundJobEntity saved = captor.getValue();
        assertThat(saved.getConcertId()).isEqualTo(concertId);
        assertThat(saved.getStatus()).isEqualTo("ENABLED");
        assertThat(saved.getEnabledAt()).isNotNull();
        assertThat(response.concertId()).isEqualTo(concertId);
        assertThat(response.status()).isEqualTo("ENABLED");
        assertThat(response.enabledAt()).isEqualTo(saved.getEnabledAt());
    }

    @Test
    void enableRefund_whenExisting_returnsUnchangedWithoutSaving() {
        UUID concertId = UUID.randomUUID();
        Instant enabledAt = Instant.parse("2026-06-27T10:00:00Z");
        RefundJobEntity existing = RefundJobEntity.builder()
                .concertId(concertId)
                .enabledAt(enabledAt)
                .status("COMPLETED")
                .build();
        when(refundJobRepository.findByConcertId(concertId)).thenReturn(Optional.of(existing));

        RefundJobResponse response = service().enableRefund(concertId);

        verify(refundJobRepository, times(0)).save(any());
        assertThat(response.concertId()).isEqualTo(concertId);
        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.enabledAt()).isEqualTo(enabledAt);
    }

    @Test
    void enableRefund_calledTwice_savesOnlyOnce() {
        UUID concertId = UUID.randomUUID();
        RefundJobEntity[] saved = new RefundJobEntity[1];
        when(refundJobRepository.findByConcertId(concertId))
                .thenAnswer(invocation -> Optional.ofNullable(saved[0]));
        when(refundJobRepository.save(any(RefundJobEntity.class)))
                .thenAnswer(invocation -> saved[0] = invocation.getArgument(0));

        RefundJobResponse first = service().enableRefund(concertId);
        RefundJobResponse second = service().enableRefund(concertId);

        verify(refundJobRepository, times(2)).findByConcertId(concertId);
        verify(refundJobRepository, times(1)).save(any(RefundJobEntity.class));
        assertThat(second).isEqualTo(first);
    }

    private RefundAdminService service() {
        return new RefundAdminService(refundJobRepository, refundJobMapper);
    }
}
