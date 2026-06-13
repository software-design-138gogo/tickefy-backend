package com.tickefy.checkin.modules.checkin.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tickefy.checkin.modules.checkin.dto.ScanRequest;
import com.tickefy.checkin.modules.checkin.dto.ScanResponse;
import com.tickefy.checkin.modules.checkin.service.CheckinService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CheckinControllerSecurityTest {

    private static final String SECRET = "dev-only-secret-minimum-32-chars-long";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CheckinService checkinService;

    @Test
    void checkinHistory_withoutToken_returnsUnauthorizedEnvelope() throws Exception {
        mockMvc.perform(get("/api/checkin/events/concert-1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void checkinHistory_withCustomerRole_returnsForbiddenEnvelope() throws Exception {
        mockMvc.perform(get("/api/checkin/events/concert-1")
                        .header("Authorization", bearer("customer-1", "CUSTOMER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void scan_withSpoofedHeader_usesJwtSubjectAsStaffId() throws Exception {
        when(checkinService.scan(any(ScanRequest.class), eq("staff-jwt")))
                .thenReturn(new ScanResponse("ACCEPTED", "ticket-1", "concert-1", "gate-A", Instant.now()));

        mockMvc.perform(post("/api/checkin/scan")
                        .header("Authorization", bearer("staff-jwt", "CHECKIN_STAFF"))
                        .header("X-User-Id", "spoofed-staff")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "qrToken": "qr-token",
                                  "concertId": "concert-1",
                                  "deviceId": "device-1",
                                  "gate": "gate-A"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.result").value("ACCEPTED"));

        verify(checkinService).scan(any(ScanRequest.class), eq("staff-jwt"));
        verify(checkinService, never()).scan(any(ScanRequest.class), eq("spoofed-staff"));
    }

    @Test
    void scan_withPrefixedCheckinStaffRole_returnsSuccess() throws Exception {
        when(checkinService.scan(any(ScanRequest.class), eq("staff-jwt")))
                .thenReturn(new ScanResponse("ACCEPTED", "ticket-1", "concert-1", "gate-A", Instant.now()));

        mockMvc.perform(post("/api/checkin/scan")
                        .header("Authorization", bearer("staff-jwt", "ROLE_CHECKIN_STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "qrToken": "qr-token",
                                  "concertId": "concert-1",
                                  "deviceId": "device-1",
                                  "gate": "gate-A"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.result").value("ACCEPTED"));
    }

    private static String bearer(String subject, String role) {
        Instant now = Instant.now();
        String token = Jwts.builder()
                .subject(subject)
                .claim("roles", List.of(role))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(3600)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
        return "Bearer " + token;
    }
}
