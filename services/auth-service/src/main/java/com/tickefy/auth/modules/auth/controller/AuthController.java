package com.tickefy.auth.modules.auth.controller;

import com.tickefy.auth.common.constants.HeaderConstants;
import com.tickefy.auth.common.response.ApiResponse;
import com.tickefy.auth.modules.auth.dto.AccessTokenResponse;
import com.tickefy.auth.modules.auth.dto.LoginRequest;
import com.tickefy.auth.modules.auth.dto.RefreshTokenRequest;
import com.tickefy.auth.modules.auth.dto.RegisterRequest;
import com.tickefy.auth.modules.auth.dto.RegisterResponse;
import com.tickefy.auth.modules.auth.dto.TokenResponse;
import com.tickefy.auth.modules.auth.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<RegisterResponse>> register(
            @Valid @RequestBody RegisterRequest request, HttpServletRequest httpRequest) {
        String requestId = getRequestId(httpRequest);
        RegisterResponse response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response, requestId));
    }

    @PostMapping("/login")
    public ApiResponse<TokenResponse> login(
            @Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        String requestId = getRequestId(httpRequest);
        TokenResponse response = authService.login(request);
        return ApiResponse.success(response, requestId);
    }

    @PostMapping("/refresh-token")
    public ApiResponse<AccessTokenResponse> refreshToken(
            @Valid @RequestBody RefreshTokenRequest request, HttpServletRequest httpRequest) {
        String requestId = getRequestId(httpRequest);
        AccessTokenResponse response = authService.refresh(request);
        return ApiResponse.success(response, requestId);
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(
            @RequestHeader("Authorization") String authorizationHeader,
            HttpServletRequest httpRequest) {
        String requestId = getRequestId(httpRequest);
        authService.logout(authorizationHeader);
        return ApiResponse.success(null, requestId);
    }

    private String getRequestId(HttpServletRequest request) {
        String fromAttr = (String) request.getAttribute(HeaderConstants.REQUEST_ID);
        if (fromAttr != null) {
            return fromAttr;
        }
        return MDC.get(HeaderConstants.REQUEST_ID);
    }
}
