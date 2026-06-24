package com.tickefy.notification.modules.notification.controller;

import com.tickefy.notification.common.response.ApiResponse;
import com.tickefy.notification.modules.notification.service.DeviceTokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications/device-tokens")
@RequiredArgsConstructor
@Tag(name = "Device Token", description = "Manage mobile/web push notification tokens")
public class DeviceTokenController {

    private final DeviceTokenService deviceTokenService;

    @Data
    public static class TokenRequest {
        private String token;
        private String deviceType; // "IOS", "ANDROID", "WEB"
        private String deviceId;
    }

    @Operation(summary = "Register device token")
    @PostMapping
    public ResponseEntity<ApiResponse<Void>> registerToken(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody TokenRequest request) {

        UUID userId = UUID.fromString(jwt.getSubject());
        deviceTokenService.registerToken(userId, request.getToken(), request.getDeviceType(), request.getDeviceId());
        return ResponseEntity.ok(ApiResponse.success(null, null));
    }

    @Operation(summary = "Unregister device token")
    @DeleteMapping
    public ResponseEntity<ApiResponse<Void>> unregisterToken(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody TokenRequest request) {

        UUID userId = UUID.fromString(jwt.getSubject());
        deviceTokenService.unregisterToken(userId, request.getToken());
        return ResponseEntity.ok(ApiResponse.success(null, null));
    }
}
