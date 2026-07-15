package com.tickefy.auth.modules.auth.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cookie attributes for the HttpOnly auth cookies (FE web flow).
 * Max-Age is NOT configured here — it is derived from {@link JwtProperties} access/refresh TTL
 * so there is a single source of truth for token lifetimes.
 */
@ConfigurationProperties(prefix = "app.cookie")
public class CookieProperties {

    /** Cookie name carrying the access JWT. */
    private String accessName = "access_token";

    /** Cookie name carrying the opaque refresh token. */
    private String refreshName = "refresh_token";

    /** Path scope for the refresh cookie (narrowed so it is only sent to /auth endpoints). */
    private String refreshPath = "/auth";

    /** Secure flag — true in prod (HTTPS), false for local http dev. */
    private boolean secure = false;

    /** SameSite policy — Lax (default), Strict, or None (None requires secure=true). */
    private String sameSite = "Lax";

    public String getAccessName() {
        return accessName;
    }

    public void setAccessName(String accessName) {
        this.accessName = accessName;
    }

    public String getRefreshName() {
        return refreshName;
    }

    public void setRefreshName(String refreshName) {
        this.refreshName = refreshName;
    }

    public String getRefreshPath() {
        return refreshPath;
    }

    public void setRefreshPath(String refreshPath) {
        this.refreshPath = refreshPath;
    }

    public boolean isSecure() {
        return secure;
    }

    public void setSecure(boolean secure) {
        this.secure = secure;
    }

    public String getSameSite() {
        return sameSite;
    }

    public void setSameSite(String sameSite) {
        this.sameSite = sameSite;
    }
}
