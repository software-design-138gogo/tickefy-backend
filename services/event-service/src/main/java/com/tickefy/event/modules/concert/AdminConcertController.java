package com.tickefy.event.modules.concert;

import com.tickefy.event.common.constants.HeaderConstants;
import com.tickefy.event.common.response.ApiResponse;
import com.tickefy.event.modules.storage.StorageService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/concerts")
@RequiredArgsConstructor
public class AdminConcertController {

    private final ConcertService concertService;
    private final StorageService storageService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ConcertResponse> createConcert(
            @Valid @RequestBody ConcertRequest body,
            JwtAuthenticationToken authentication,
            HttpServletRequest request) {
        String requestId = (String) request.getAttribute(HeaderConstants.REQUEST_ID);
        UUID userId = actorId(authentication);
        ConcertResponse response = concertService.createConcert(body, userId);
        return ApiResponse.success(response, requestId);
    }

    @PutMapping("/{id}")
    public ApiResponse<ConcertResponse> updateConcert(
            @PathVariable UUID id,
            @Valid @RequestBody ConcertRequest body,
            JwtAuthenticationToken authentication,
            HttpServletRequest request) {
        String requestId = (String) request.getAttribute(HeaderConstants.REQUEST_ID);
        return ApiResponse.success(
                concertService.updateConcert(
                        id, body, actorId(authentication), isAdmin(authentication)),
                requestId);
    }

    @PostMapping("/{id}/publish")
    public ApiResponse<ConcertResponse> publishConcert(
            @PathVariable UUID id,
            JwtAuthenticationToken authentication,
            HttpServletRequest request) {
        String requestId = (String) request.getAttribute(HeaderConstants.REQUEST_ID);
        return ApiResponse.success(
                concertService.publishConcert(
                        id, actorId(authentication), isAdmin(authentication)),
                requestId);
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<ConcertResponse> cancelConcert(
            @PathVariable UUID id,
            @RequestParam(required = false, defaultValue = "") String reason,
            JwtAuthenticationToken authentication,
            HttpServletRequest request) {
        String requestId = (String) request.getAttribute(HeaderConstants.REQUEST_ID);
        return ApiResponse.success(
                concertService.cancelConcert(
                        id, reason, actorId(authentication), isAdmin(authentication)),
                requestId);
    }

    @GetMapping("/upload-url")
    public ApiResponse<Map<String, String>> getUploadUrl(
            @RequestParam UUID concertId,
            @RequestParam String fileName,
            @RequestParam(defaultValue = "image/svg+xml") String contentType,
            JwtAuthenticationToken authentication,
            HttpServletRequest request) {
        String requestId = (String) request.getAttribute(HeaderConstants.REQUEST_ID);
        concertService.requireManageAccess(
                concertId, actorId(authentication), isAdmin(authentication));
        String objectKey = "seat-maps/" + concertId + "/" + fileName;
        String url = storageService.generatePresignedUploadUrl(objectKey, contentType);
        return ApiResponse.success(Map.of("uploadUrl", url, "objectKey", objectKey), requestId);
    }

    private UUID actorId(JwtAuthenticationToken authentication) {
        return UUID.fromString(authentication.getToken().getSubject());
    }

    private boolean isAdmin(JwtAuthenticationToken authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_ADMIN"::equals);
    }
}
