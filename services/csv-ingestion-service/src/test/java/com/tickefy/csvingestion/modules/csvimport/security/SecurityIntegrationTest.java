package com.tickefy.csvingestion.modules.csvimport.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.jsonwebtoken.Jwts;
import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * T-csv-3a-1: Security integration tests for csv-ingestion-service.
 *
 * <p>Acceptance criteria covered:
 * <ul>
 *   <li>AC-1: public endpoint /actuator/health accessible without token (200).</li>
 *   <li>AC-2: protected endpoint without token returns 401 UNAUTHORIZED.</li>
 *   <li>AC-3: valid RS256 JWT (correct issuer + signature) passes filter — request reaches
 *             dispatcher (404, not 401).</li>
 *   <li>AC-4: forged token (wrong signing key) returns 401 INVALID_TOKEN.</li>
 *   <li>AC-5: token with wrong issuer returns 401 INVALID_TOKEN.</li>
 * </ul>
 *
 * <p>RSA keypair is generated at runtime — no private key committed to source.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityIntegrationTest {

    // --- Runtime RSA keypair generation ---

    /** Primary keypair: public key registered with JwtKeyProvider; tokens signed with this. */
    private static KeyPair PRIMARY_PAIR;

    /** Foreign keypair: used to forge tokens — signature won't match primary public key. */
    private static KeyPair FOREIGN_PAIR;

    /** Temp file path holding the PEM-encoded public key of PRIMARY_PAIR. */
    private static String publicKeyFilePath;

    static {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            PRIMARY_PAIR = gen.generateKeyPair();
            FOREIGN_PAIR = gen.generateKeyPair();

            // Write PRIMARY public key as PEM to a temp file so JwtKeyProvider can load it
            Path tmp = Files.createTempFile("jwt-test-public-", ".pem");
            byte[] encoded = PRIMARY_PAIR.getPublic().getEncoded();
            String pem = "-----BEGIN PUBLIC KEY-----\n"
                    + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded)
                    + "\n-----END PUBLIC KEY-----\n";
            Files.writeString(tmp, pem);
            publicKeyFilePath = "file:" + tmp.toAbsolutePath().toString().replace("\\", "/");
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate RSA keypair for test", e);
        }
    }

    @DynamicPropertySource
    static void overrideJwtPublicKey(DynamicPropertyRegistry registry) {
        // Override the public key path so JwtKeyProvider loads the key we generated above.
        // Override issuer to a known value so token claims match exactly.
        registry.add("app.jwt.public-key", () -> publicKeyFilePath);
        registry.add("app.jwt.issuer", () -> "tickefy-auth-service");
    }

    @Autowired
    private MockMvc mockMvc;

    // -----------------------------------------------------------------------
    // AC-1: public health endpoint — no token → 200
    // -----------------------------------------------------------------------

    /**
     * AC-1: /actuator/health is permitAll → must return 200 without any auth header.
     */
    @Test
    void ac1_healthEndpoint_noToken_returns200() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    // -----------------------------------------------------------------------
    // AC-2: protected endpoint — no token → 401 UNAUTHORIZED
    // -----------------------------------------------------------------------

    /**
     * AC-2: POST /api/admin/csv-import without Authorization header must return 401.
     * Body envelope: { success: false, error.code: "UNAUTHORIZED" }.
     */
    @Test
    void ac2_protectedEndpoint_noToken_returns401Unauthorized() throws Exception {
        mockMvc.perform(post("/api/admin/csv-import")
                        .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    // -----------------------------------------------------------------------
    // AC-3: valid RS256 token — filter passes → NOT 401
    // -----------------------------------------------------------------------

    /**
     * AC-3: A token signed with the registered key and correct issuer must pass the filter.
     * No controller handles /api/admin/csv-import yet → expected 404.
     * Assert status != 401 (filter did not block).
     */
    @Test
    void ac3_validToken_filterPasses_not401() throws Exception {
        String token = buildValidToken("user-uuid-001", List.of("ADMIN"), PRIMARY_PAIR);

        mockMvc.perform(post("/api/admin/csv-import")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    if (status == 401) {
                        throw new AssertionError(
                                "Expected filter to PASS (status != 401) but got 401. "
                                + "Response: " + result.getResponse().getContentAsString());
                    }
                });
    }

    // -----------------------------------------------------------------------
    // AC-4: forged token (wrong signing key) → 401 INVALID_TOKEN
    // -----------------------------------------------------------------------

    /**
     * AC-4: Token signed with a different RSA keypair (FOREIGN_PAIR) has a signature that the
     * filter cannot verify with PRIMARY public key → must return 401 with error.code INVALID_TOKEN.
     */
    @Test
    void ac4_forgedToken_wrongKey_returns401InvalidToken() throws Exception {
        String forgedToken = buildValidToken("attacker-001", List.of("ADMIN"), FOREIGN_PAIR);

        mockMvc.perform(post("/api/admin/csv-import")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + forgedToken)
                        .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_TOKEN"));
    }

    // -----------------------------------------------------------------------
    // AC-5: token with wrong issuer → 401 INVALID_TOKEN
    // -----------------------------------------------------------------------

    /**
     * AC-5: Token signed with the correct key but carrying a wrong issuer claim must be rejected
     * by JwtVerifier (requireIssuer check) → 401 INVALID_TOKEN.
     */
    @Test
    void ac5_wrongIssuer_returns401InvalidToken() throws Exception {
        String wrongIssuerToken = Jwts.builder()
                .subject("user-uuid-002")
                .issuer("evil-issuer")
                .claim("roles", List.of("ADMIN"))
                .expiration(new Date(System.currentTimeMillis() + 3_600_000L))
                .signWith(PRIMARY_PAIR.getPrivate(), Jwts.SIG.RS256)
                .compact();

        mockMvc.perform(post("/api/admin/csv-import")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + wrongIssuerToken)
                        .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_TOKEN"));
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    private static String buildValidToken(String subject, List<String> roles, KeyPair pair) {
        return Jwts.builder()
                .subject(subject)
                .issuer("tickefy-auth-service")
                .claim("roles", roles)
                .expiration(new Date(System.currentTimeMillis() + 3_600_000L))
                .signWith(pair.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }
}
