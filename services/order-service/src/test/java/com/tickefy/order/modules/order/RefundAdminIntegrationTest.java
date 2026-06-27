package com.tickefy.order.modules.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickefy.order.BaseIntegrationTest;
import com.tickefy.order.TestJwtHelper;
import com.tickefy.order.modules.order.entity.RefundJobEntity;
import com.tickefy.order.modules.order.repository.RefundJobRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class RefundAdminIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RefundJobRepository refundJobRepository;

    private String adminToken;
    private String audienceToken;

    @BeforeEach
    void setUp() {
        refundJobRepository.deleteAll();
        adminToken = tokenFor("ADMIN");
        audienceToken = tokenFor("AUDIENCE");
    }

    @Test
    void adminPost_createsJob_andSecondPostIsNoOp() throws Exception {
        UUID concertId = UUID.randomUUID();
        String body = objectMapper.writeValueAsString(Map.of("concertId", concertId));

        mockMvc.perform(post("/orders/admin/refund-jobs")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.concertId").value(concertId.toString()))
                .andExpect(jsonPath("$.data.status").value("ENABLED"))
                .andExpect(jsonPath("$.data.enabledAt").isNotEmpty());

        RefundJobEntity first = refundJobRepository.findByConcertId(concertId).orElseThrow();
        assertThat(first.getEnabledAt()).isNotNull();
        assertThat(refundJobRepository.count()).isEqualTo(1);

        mockMvc.perform(post("/orders/admin/refund-jobs")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.concertId").value(concertId.toString()))
                .andExpect(jsonPath("$.data.status").value("ENABLED"));

        RefundJobEntity replayed = refundJobRepository.findByConcertId(concertId).orElseThrow();
        assertThat(refundJobRepository.count()).isEqualTo(1);
        assertThat(replayed.getEnabledAt()).isEqualTo(first.getEnabledAt());
    }

    @Test
    void audiencePost_returns403AndDoesNotCreateJob() throws Exception {
        mockMvc.perform(post("/orders/admin/refund-jobs")
                        .header("Authorization", "Bearer " + audienceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        assertThat(refundJobRepository.count()).isZero();
    }

    @Test
    void noTokenPost_returns401AndDoesNotCreateJob() throws Exception {
        mockMvc.perform(post("/orders/admin/refund-jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

        assertThat(refundJobRepository.count()).isZero();
    }

    @Test
    void nullConcertId_returns400AndDoesNotCreateJob() throws Exception {
        mockMvc.perform(post("/orders/admin/refund-jobs")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"concertId\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details.concertId").value("concertId is required"));

        assertThat(refundJobRepository.count()).isZero();
    }

    private String validBody() throws Exception {
        return objectMapper.writeValueAsString(Map.of("concertId", UUID.randomUUID()));
    }

    private String tokenFor(String role) {
        return TestJwtHelper.generateToken(UUID.randomUUID().toString(), List.of(role));
    }
}
