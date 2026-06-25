package com.tickefy.checkin.modules.vip;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tickefy.checkin.modules.vip.service.VipProjectionService;
import com.tickefy.checkin.support.JwtTestTokenFactory;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Security layer tests for VipQueryController.
 * Mirrors CheckinControllerSecurityTest pattern (surefire, no Docker, H2 in-memory).
 *
 * AC coverage:
 * AC-SEC-1  no token → 401 UNAUTHORIZED
 * AC-SEC-2  role AUDIENCE → 403 FORBIDDEN
 * AC-SEC-3  role CHECKIN_STAFF → 200
 * AC-SEC-4  role ADMIN → 200
 * AC-SEC-5  path /api/checkin/concerts/{concertId}/vip-guests
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VipQueryControllerSecurityTest {

    private static final UUID CONCERT_ID = UUID.fromString("bbbbbbbb-aaaa-cccc-dddd-111111111111");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VipProjectionService vipProjectionService;

    @BeforeEach
    void setUp() {
        when(vipProjectionService.getVipGuests(any(), any(), any()))
                .thenReturn(Page.empty());
    }

    // -------------------------------------------------------------------------
    // AC-SEC-1  no token → 401 UNAUTHORIZED
    // -------------------------------------------------------------------------

    @Test
    void getVipGuests_withoutToken_returnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/checkin/concerts/{concertId}/vip-guests", CONCERT_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    // -------------------------------------------------------------------------
    // AC-SEC-2  role AUDIENCE → 403 FORBIDDEN
    // -------------------------------------------------------------------------

    @Test
    void getVipGuests_withAudienceRole_returnsForbidden() throws Exception {
        mockMvc.perform(get("/api/checkin/concerts/{concertId}/vip-guests", CONCERT_ID)
                        .header("Authorization", JwtTestTokenFactory.bearer("user-1", "AUDIENCE")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    // -------------------------------------------------------------------------
    // AC-SEC-3  role CHECKIN_STAFF → 200
    // -------------------------------------------------------------------------

    @Test
    void getVipGuests_withCheckinStaffRole_returnsOk() throws Exception {
        mockMvc.perform(get("/api/checkin/concerts/{concertId}/vip-guests", CONCERT_ID)
                        .header("Authorization", JwtTestTokenFactory.bearer("staff-1", "CHECKIN_STAFF")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    // -------------------------------------------------------------------------
    // AC-SEC-4  role ADMIN → 200
    // -------------------------------------------------------------------------

    @Test
    void getVipGuests_withAdminRole_returnsOk() throws Exception {
        mockMvc.perform(get("/api/checkin/concerts/{concertId}/vip-guests", CONCERT_ID)
                        .header("Authorization", JwtTestTokenFactory.bearer("admin-1", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    // -------------------------------------------------------------------------
    // AC-SEC-5  wrong issuer → 401 INVALID_TOKEN (ensure path is right)
    // -------------------------------------------------------------------------

    @Test
    void getVipGuests_withInvalidToken_returnsInvalidToken() throws Exception {
        mockMvc.perform(get("/api/checkin/concerts/{concertId}/vip-guests", CONCERT_ID)
                        .header("Authorization", "Bearer not.a.jwt.at.all"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_TOKEN"));
    }
}
