package com.tickefy.auth.modules.auth.dto;

/**
 * Body for POST /auth/refresh-token. The refresh token is optional in the body because the
 * primary source is the HttpOnly {@code refresh_token} cookie; the body is kept only for
 * backward compatibility (tests / service clients). The controller rejects the request when
 * neither cookie nor body carries a token.
 */
public record RefreshTokenRequest(String refreshToken) {}
