package com.tickefy.auth.modules.auth.controller;

import com.tickefy.auth.common.constants.HeaderConstants;
import com.tickefy.auth.common.exception.ApiException;
import com.tickefy.auth.common.exception.ErrorCode;
import com.tickefy.auth.common.response.ApiResponse;
import com.tickefy.auth.modules.auth.dto.AccessTokenResponse;
import com.tickefy.auth.modules.auth.dto.LoginRequest;
import com.tickefy.auth.modules.auth.dto.RefreshTokenRequest;
import com.tickefy.auth.modules.auth.dto.RegisterRequest;
import com.tickefy.auth.modules.auth.dto.RegisterResponse;
import com.tickefy.auth.modules.auth.dto.TokenResponse;
import com.tickefy.auth.modules.auth.security.CookieFactory;
import com.tickefy.auth.modules.auth.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
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
    private final CookieFactory cookieFactory;

    public AuthController(AuthService authService, CookieFactory cookieFactory) {
        this.authService = authService;
        this.cookieFactory = cookieFactory;
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<RegisterResponse>> register(
            @Valid @RequestBody RegisterRequest request, HttpServletRequest httpRequest) {
        String requestId = getRequestId(httpRequest);
        RegisterResponse response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response, requestId));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<TokenResponse>> login(
            @Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        String requestId = getRequestId(httpRequest);
        TokenResponse response = authService.login(request);

        // Body keeps both tokens (backward compatible); cookies are added for the FE web flow.
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.SET_COOKIE, cookieFactory.accessCookie(response.accessToken()).toString());
        headers.add(HttpHeaders.SET_COOKIE, cookieFactory.refreshCookie(response.refreshToken()).toString());
        return ResponseEntity.ok().headers(headers).body(ApiResponse.success(response, requestId));
    }

    @PostMapping("/refresh-token")
    public ResponseEntity<ApiResponse<AccessTokenResponse>> refreshToken(
            @RequestBody(required = false) RefreshTokenRequest request, HttpServletRequest httpRequest) {
        String requestId = getRequestId(httpRequest);

        // Primary source = HttpOnly refresh cookie; fall back to body for backward compatibility.
        String refreshTokenRaw = cookieFactory.readRefreshToken(httpRequest)
                .orElseGet(() -> request != null ? request.refreshToken() : null);
        if (refreshTokenRaw == null || refreshTokenRaw.isBlank()) {
            throw new ApiException(
                    ErrorCode.INVALID_TOKEN, "Refresh token is required", HttpStatus.UNAUTHORIZED);
        }

        AccessTokenResponse response = authService.refresh(new RefreshTokenRequest(refreshTokenRaw));

        // Refresh is not rotated (kept as-is); only the access cookie is refreshed.
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.SET_COOKIE, cookieFactory.accessCookie(response.accessToken()).toString());
        return ResponseEntity.ok().headers(headers).body(ApiResponse.success(response, requestId));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            @RequestHeader("Authorization") String authorizationHeader,
            HttpServletRequest httpRequest) {
        String requestId = getRequestId(httpRequest);
        authService.logout(authorizationHeader);

        // Clear both cookies regardless of how the FE authenticated (header is required by the
        // security filter, so the access token is always present on this authenticated endpoint).
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.SET_COOKIE, cookieFactory.clearAccessCookie().toString());
        headers.add(HttpHeaders.SET_COOKIE, cookieFactory.clearRefreshCookie().toString());
        return ResponseEntity.ok().headers(headers).body(ApiResponse.success(null, requestId));
    }

    private String getRequestId(HttpServletRequest request) {
        String fromAttr = (String) request.getAttribute(HeaderConstants.REQUEST_ID);
        if (fromAttr != null) {
            return fromAttr;
        }
        return MDC.get(HeaderConstants.REQUEST_ID);
    }
}
