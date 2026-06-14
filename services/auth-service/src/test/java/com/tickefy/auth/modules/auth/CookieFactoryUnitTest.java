package com.tickefy.auth.modules.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.tickefy.auth.modules.auth.security.CookieFactory;
import com.tickefy.auth.modules.auth.security.CookieProperties;
import com.tickefy.auth.modules.auth.security.JwtProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseCookie;

/**
 * Pure Mockito unit tests for {@link CookieFactory}. No Spring context, no Docker.
 * Verifies cookie attributes (HttpOnly/Secure/SameSite/Path/Max-Age) derive correctly from
 * {@link CookieProperties} + {@link JwtProperties}, and that refresh-token reading prefers the cookie.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class CookieFactoryUnitTest {

    @Mock
    private HttpServletRequest request;

    private CookieFactory cookieFactory;

    @BeforeEach
    void setUp() {
        CookieProperties cookieProperties = new CookieProperties();
        cookieProperties.setAccessName("access_token");
        cookieProperties.setRefreshName("refresh_token");
        cookieProperties.setRefreshPath("/auth");
        cookieProperties.setSecure(false);
        cookieProperties.setSameSite("Lax");

        JwtProperties jwtProperties = new JwtProperties();
        jwtProperties.setAccessTtl(Duration.ofMinutes(15));
        jwtProperties.setRefreshTtl(Duration.ofDays(7));

        cookieFactory = new CookieFactory(cookieProperties, jwtProperties);
    }

    @Test
    @DisplayName("accessCookie: HttpOnly, Path=/, Max-Age = access TTL (900s), SameSite=Lax, Secure=false")
    void accessCookie_attributes() {
        ResponseCookie cookie = cookieFactory.accessCookie("the-access-jwt");

        assertThat(cookie.getName()).isEqualTo("access_token");
        assertThat(cookie.getValue()).isEqualTo("the-access-jwt");
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.isSecure()).isFalse();
        assertThat(cookie.getSameSite()).isEqualTo("Lax");
        assertThat(cookie.getPath()).isEqualTo("/");
        assertThat(cookie.getMaxAge()).isEqualTo(Duration.ofSeconds(900));
    }

    @Test
    @DisplayName("refreshCookie: HttpOnly, Path=/auth, Max-Age = refresh TTL (604800s)")
    void refreshCookie_attributes() {
        ResponseCookie cookie = cookieFactory.refreshCookie("opaque-refresh");

        assertThat(cookie.getName()).isEqualTo("refresh_token");
        assertThat(cookie.getValue()).isEqualTo("opaque-refresh");
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getPath()).isEqualTo("/auth");
        assertThat(cookie.getMaxAge()).isEqualTo(Duration.ofSeconds(604800));
    }

    @Test
    @DisplayName("secure=true + SameSite=None propagate to built cookie")
    void secureNone_propagate() {
        CookieProperties props = new CookieProperties();
        props.setSecure(true);
        props.setSameSite("None");
        JwtProperties jwt = new JwtProperties();
        CookieFactory factory = new CookieFactory(props, jwt);

        ResponseCookie cookie = factory.accessCookie("x");

        assertThat(cookie.isSecure()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo("None");
    }

    @Test
    @DisplayName("clearAccessCookie / clearRefreshCookie: Max-Age=0, empty value, matching path")
    void clearCookies_expireImmediately() {
        ResponseCookie clearAccess = cookieFactory.clearAccessCookie();
        ResponseCookie clearRefresh = cookieFactory.clearRefreshCookie();

        assertThat(clearAccess.getMaxAge()).isEqualTo(Duration.ZERO);
        assertThat(clearAccess.getValue()).isEmpty();
        assertThat(clearAccess.getPath()).isEqualTo("/");

        assertThat(clearRefresh.getMaxAge()).isEqualTo(Duration.ZERO);
        assertThat(clearRefresh.getValue()).isEmpty();
        assertThat(clearRefresh.getPath()).isEqualTo("/auth");
    }

    @Test
    @DisplayName("readRefreshToken: returns value from matching cookie")
    void readRefreshToken_presentCookie() {
        when(request.getCookies()).thenReturn(new Cookie[] {
                new Cookie("other", "junk"),
                new Cookie("refresh_token", "my-refresh-value")
        });

        Optional<String> result = cookieFactory.readRefreshToken(request);

        assertThat(result).contains("my-refresh-value");
    }

    @Test
    @DisplayName("readRefreshToken: empty when no cookies present")
    void readRefreshToken_noCookies() {
        when(request.getCookies()).thenReturn(null);

        assertThat(cookieFactory.readRefreshToken(request)).isEmpty();
    }

    @Test
    @DisplayName("readRefreshToken: empty when cookie value is blank")
    void readRefreshToken_blankValue() {
        when(request.getCookies()).thenReturn(new Cookie[] {
                new Cookie("refresh_token", "")
        });

        assertThat(cookieFactory.readRefreshToken(request)).isEmpty();
    }
}
