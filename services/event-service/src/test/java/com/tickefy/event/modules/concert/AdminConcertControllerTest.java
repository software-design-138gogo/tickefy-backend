package com.tickefy.event.modules.concert;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickefy.event.common.constants.HeaderConstants;
import com.tickefy.event.common.exception.ApiException;
import com.tickefy.event.common.exception.ErrorCode;
import com.tickefy.event.common.response.ApiResponse;
import com.tickefy.event.config.SecurityConfig;
import com.tickefy.event.modules.storage.StorageService;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminConcertController.class)
@Import(SecurityConfig.class)
class AdminConcertControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ConcertService concertService;

    @MockBean
    private StorageService storageService;

    @MockBean
    private org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder;

    private ConcertRequest validRequest;
    private ConcertResponse mockResponse;
    private UUID userId;
    private UUID concertId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        concertId = UUID.randomUUID();

        validRequest = new ConcertRequest();
        validRequest.setTitle("Test Concert");
        validRequest.setEventDate(Instant.now().plusSeconds(86400));
        validRequest.setSaleStartAt(Instant.now());
        validRequest.setSaleEndAt(Instant.now().plusSeconds(3600));
        validRequest.setVenueId(UUID.randomUUID());
        validRequest.setArtistIds(java.util.List.of(UUID.randomUUID()));
        validRequest.setZones(java.util.List.of());

        Concert concert = new Concert();
        org.springframework.test.util.ReflectionTestUtils.setField(concert, "id", concertId);
        concert.setTitle("Test Concert");
        concert.setCreatedBy(userId);
        concert.setStatus(ConcertStatus.DRAFT);
        concert.setEventDate(validRequest.getEventDate());
        concert.setSaleStartAt(validRequest.getSaleStartAt());
        concert.setSaleEndAt(validRequest.getSaleEndAt());

        mockResponse = ConcertResponse.from(concert);
    }

    @Test
    void createConcert_WithoutToken_ShouldReturn401() throws Exception {
        mockMvc.perform(post("/admin/concerts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createConcert_WithUserRole_ShouldReturn403() throws Exception {
        mockMvc.perform(post("/admin/concerts")
                        .with(jwt().jwt(jwt -> jwt.subject(userId.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void createConcert_WithOrganizerRole_ShouldReturn201() throws Exception {
        when(concertService.createConcert(any(ConcertRequest.class), eq(userId))).thenReturn(mockResponse);

        mockMvc.perform(post("/admin/concerts")
                        .with(jwt().jwt(jwt -> jwt.subject(userId.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_ORGANIZER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(concertId.toString()))
                .andExpect(jsonPath("$.data.organizerId").value(userId.toString()));

        verify(concertService).createConcert(any(ConcertRequest.class), eq(userId));
    }

    @Test
    void updateConcert_WithNotOwner_ShouldReturn403() throws Exception {
        // Mock ConcertService throwing ApiException (CONCERT_ACCESS_DENIED)
        when(concertService.updateConcert(eq(concertId), any(ConcertRequest.class), eq(userId), eq(false)))
                .thenThrow(new ApiException(ErrorCode.CONCERT_ACCESS_DENIED, "Access denied", HttpStatus.FORBIDDEN, Map.of()));

        mockMvc.perform(put("/admin/concerts/{id}", concertId)
                        .with(jwt().jwt(jwt -> jwt.subject(userId.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_ORGANIZER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value(ErrorCode.CONCERT_ACCESS_DENIED.name()));
    }

    @Test
    void updateConcert_WithAdminRole_ShouldSucceed() throws Exception {
        when(concertService.updateConcert(eq(concertId), any(ConcertRequest.class), eq(userId), eq(true))).thenReturn(mockResponse);

        mockMvc.perform(put("/admin/concerts/{id}", concertId)
                        .with(jwt().jwt(jwt -> jwt.subject(userId.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest)))
                .andExpect(status().isOk());

        verify(concertService).updateConcert(eq(concertId), any(ConcertRequest.class), eq(userId), eq(true));
    }
}
