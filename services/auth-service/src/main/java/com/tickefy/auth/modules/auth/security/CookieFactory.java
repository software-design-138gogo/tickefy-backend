package com.tickefy.auth.modules.auth.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Builds and reads the HttpOnly auth cookies (access + refresh) for the FE web flow.
 *
 * <p>Cookies are HttpOnly (not readable by JS — XSS cannot steal them) with attributes
 * (Secure / SameSite) driven by {@link CookieProperties}. Max-Age is taken from
 * {@link JwtProperties} so cookie lifetime always matches token TTL.
 *
 * <p>NOTE: cookies are a FE convenience only. Cross-service / API authentication uses the
 * {@code Authorization: Bearer} header — {@code JwtAuthenticationFilter} reads the header, NOT
 * cookies. The access cookie is forward-looking (prod-via-gateway); the refresh cookie is the
 * primary security feature (FE stores the refresh token nowhere else).
 */
@Component
public class CookieFactory {

    private final CookieProperties cookieProperties;
    private final JwtProperties jwtProperties;

    public CookieFactory(CookieProperties cookieProperties, JwtProperties jwtProperties) {
        this.cookieProperties = cookieProperties;
        this.jwtProperties = jwtProperties;
    }

    /** Set-Cookie for the access JWT, Path=/, Max-Age = access TTL. */
    public ResponseCookie accessCookie(String accessToken) {
        return build(cookieProperties.getAccessName(), accessToken, "/",
                jwtProperties.getAccessTtl().getSeconds());
    }

    /** Set-Cookie for the opaque refresh token, Path = refreshPath, Max-Age = refresh TTL. */
    public ResponseCookie refreshCookie(String refreshTokenRaw) {
        return build(cookieProperties.getRefreshName(), refreshTokenRaw, cookieProperties.getRefreshPath(),
                jwtProperties.getRefreshTtl().getSeconds());
    }

    /** Set-Cookie that expires the access cookie immediately (Max-Age=0). */
    public ResponseCookie clearAccessCookie() {
        return build(cookieProperties.getAccessName(), "", "/", 0);
    }

    /** Set-Cookie that expires the refresh cookie immediately (Max-Age=0). */
    public ResponseCookie clearRefreshCookie() {
        return build(cookieProperties.getRefreshName(), "", cookieProperties.getRefreshPath(), 0);
    }

    /** Reads the refresh token from the request cookie, if present and non-blank. */
    public Optional<String> readRefreshToken(HttpServletRequest request) {
        return readCookie(request, cookieProperties.getRefreshName());
    }

    private ResponseCookie build(String name, String value, String path, long maxAgeSeconds) {
        return ResponseCookie.from(name, value == null ? "" : value)
                .httpOnly(true)
                .secure(cookieProperties.isSecure())
                .sameSite(cookieProperties.getSameSite())
                .path(path)
                .maxAge(maxAgeSeconds)
                .build();
    }

    private Optional<String> readCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
                .filter(c -> name.equals(c.getName()))
                .map(Cookie::getValue)
                .filter(v -> v != null && !v.isBlank())
                .findFirst();
    }
}
