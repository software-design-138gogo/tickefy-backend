package com.tickefy.csvingestion.modules.csvimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickefy.csvingestion.modules.csvimport.entity.VipGuestEntity;
import com.tickefy.csvingestion.modules.csvimport.repository.VipGuestRepository;
import io.jsonwebtoken.Jwts;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * T-csv-6a: Integration tests for GET /internal/concerts/{concertId}/vip-guests.
 *
 * <p>Acceptance criteria covered:
 * <ul>
 *   <li>AC6a-1 (list by concert): concert A → 3 rows, concert B isolated, fields correct.</li>
 *   <li>AC6a-2 (email filter): exact match, case-insensitive, NONE→empty 200 (not 404).</li>
 *   <li>AC6a-3 (empty concert): random concertId → 200 empty page (not 404).</li>
 *   <li>AC6a-4 (pagination): size=2→content 2/totalElements 3/totalPages 2; page=1→content 1.</li>
 *   <li>AC6a-5 (security): no-token→401; AUDIENCE→403; CHECKIN_STAFF→200; ADMIN→200.</li>
 *   <li>AC6a-6 (no-PII-leak): response row exposes exactly {email,fullName,ticketTypeId,ticketTypeName}.</li>
 * </ul>
 *
 * <p>Role claim: JwtAuthenticationFilter reads "roles" (List) and prepends "ROLE_" prefix to each
 * element via {@code new SimpleGrantedAuthority("ROLE_" + r)}. Therefore we place bare role names
 * (e.g. "CHECKIN_STAFF", "ADMIN", "AUDIENCE") in the "roles" claim — NOT "ROLE_CHECKIN_STAFF".
 * SecurityConfig requires hasAnyRole("CHECKIN_STAFF","ADMIN") which Spring resolves against
 * "ROLE_CHECKIN_STAFF"/"ROLE_ADMIN" — so the mapping is correct.
 *
 * <p>Infrastructure: Testcontainers Postgres + Flyway (public schema), RSA keypair at runtime,
 * dummy MinIO/event-service stubs (not called by this endpoint).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = "app.messaging.outbox.enabled=false")
class CsvInternalVipGuestIntegrationTest {

    // -----------------------------------------------------------------------
    // Concert IDs
    // -----------------------------------------------------------------------

    static final UUID CONCERT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    static final UUID CONCERT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    static final UUID TICKET_TYPE_VIP = UUID.fromString("cccccccc-0000-0000-0000-000000000003");

    // -----------------------------------------------------------------------
    // Testcontainers — Postgres
    // -----------------------------------------------------------------------

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("csv_ingestion_test")
                    .withUsername("csv_test")
                    .withPassword("csv_test");

    // -----------------------------------------------------------------------
    // RSA keypair — generated at runtime (no private key in source)
    // -----------------------------------------------------------------------

    static KeyPair PRIMARY_PAIR;
    static String publicKeyFilePath;

    static {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            PRIMARY_PAIR = gen.generateKeyPair();

            Path tmp = Files.createTempFile("jwt-vipguest-test-public-", ".pem");
            byte[] encoded = PRIMARY_PAIR.getPublic().getEncoded();
            String pem = "-----BEGIN PUBLIC KEY-----\n"
                    + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded)
                    + "\n-----END PUBLIC KEY-----\n";
            Files.writeString(tmp, pem);
            publicKeyFilePath = "file:" + tmp.toAbsolutePath().toString().replace("\\", "/");
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate RSA keypair for VIP guest test", e);
        }
    }

    // -----------------------------------------------------------------------
    // @DynamicPropertySource — wire infra
    // -----------------------------------------------------------------------

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        // Postgres
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> "public");
        registry.add("spring.flyway.schemas", () -> "public");
        registry.add("spring.flyway.default-schema", () -> "public");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("app.database.schema", () -> "public");

        // MinIO — dummy (not called by this endpoint)
        registry.add("app.object-storage.endpoint", () -> "http://localhost:9999");
        registry.add("app.object-storage.access-key", () -> "dummy");
        registry.add("app.object-storage.secret-key", () -> "dummydummy");
        registry.add("app.object-storage.region", () -> "us-east-1");
        registry.add("app.object-storage.bucket", () -> "tickefy-csv");

        // Event service — dummy (not called by this endpoint)
        registry.add("app.event.base-url", () -> "http://localhost:9998");

        // JWT public key — runtime generated
        registry.add("app.jwt.public-key", () -> publicKeyFilePath);
        registry.add("app.jwt.issuer", () -> "tickefy-auth-service");
    }

    // -----------------------------------------------------------------------
    // Spring beans
    // -----------------------------------------------------------------------

    @Autowired
    MockMvc mockMvc;

    @Autowired
    VipGuestRepository vipGuestRepository;

    @Autowired
    ObjectMapper objectMapper;

    // -----------------------------------------------------------------------
    // Setup — clean vip_guests before each test
    // -----------------------------------------------------------------------

    @BeforeEach
    void cleanDb() {
        vipGuestRepository.deleteAll();
    }

    // -----------------------------------------------------------------------
    // Token helpers
    //
    // normalizeRole logic (JwtAuthenticationFilter lines 60-66):
    //   rawRoles = claims.get("roles", List.class)
    //   rawRoles.stream()...map(r -> new SimpleGrantedAuthority("ROLE_" + r))
    //
    // So claim value "CHECKIN_STAFF" → authority "ROLE_CHECKIN_STAFF"
    //    claim value "ADMIN"         → authority "ROLE_ADMIN"
    //    claim value "AUDIENCE"      → authority "ROLE_AUDIENCE"
    //
    // hasAnyRole("CHECKIN_STAFF","ADMIN") checks for "ROLE_CHECKIN_STAFF"/"ROLE_ADMIN" → PASS.
    // hasAnyRole("AUDIENCE") → "ROLE_AUDIENCE" not in set → 403.
    // -----------------------------------------------------------------------

    /** Build RS256 token with given role names placed bare in "roles" claim. */
    private static String buildToken(String sub, List<String> roles) {
        return Jwts.builder()
                .subject(sub)
                .issuer("tickefy-auth-service")
                .claim("roles", roles)
                .expiration(new Date(System.currentTimeMillis() + 3_600_000L))
                .signWith(PRIMARY_PAIR.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }

    // -----------------------------------------------------------------------
    // Seed helper
    // -----------------------------------------------------------------------

    private VipGuestEntity seedGuest(UUID concertId, String email, String fullName,
                                     UUID ticketTypeId, String ticketTypeName) {
        VipGuestEntity e = VipGuestEntity.builder()
                .concertId(concertId)
                .email(email)           // stored lowercase per import dedup convention
                .fullName(fullName)
                .ticketTypeId(ticketTypeId)
                .ticketTypeName(ticketTypeName)
                .importJobId(UUID.randomUUID())
                .build();
        return vipGuestRepository.save(e);
    }

    // -----------------------------------------------------------------------
    // AC6a-1: list by concert — 3 rows for A, concert B isolated, field shape correct
    // -----------------------------------------------------------------------

    @Test
    void ac6a1_listByConcert_returns3Rows_concertBIsolated_fieldsCorrect() throws Exception {
        UUID ttId = UUID.randomUUID();
        seedGuest(CONCERT_A, "alice@example.com", "Alice Nguyen", ttId, "VIP");
        seedGuest(CONCERT_A, "bob@example.com", "Bob Tran", ttId, "VIP");
        seedGuest(CONCERT_A, "carol@example.com", "Carol Le", ttId, "VIP");
        // Concert B must not bleed into A results
        seedGuest(CONCERT_B, "dave@example.com", "Dave Pham", ttId, "VVIP");

        String token = buildToken("svc-checkin-001", List.of("CHECKIN_STAFF"));

        mockMvc.perform(get("/internal/concerts/" + CONCERT_A + "/vip-guests")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                // 3 rows for concert A
                .andExpect(jsonPath("$.data.content.length()").value(3))
                .andExpect(jsonPath("$.data.totalElements").value(3))
                // At least 1 row has correct field values
                .andExpect(jsonPath("$.data.content[?(@.email=='alice@example.com')].fullName")
                        .value("Alice Nguyen"))
                .andExpect(jsonPath("$.data.content[?(@.email=='alice@example.com')].ticketTypeName")
                        .value("VIP"));
    }

    // -----------------------------------------------------------------------
    // AC6a-2: email filter — exact match, case-insensitive query, NONE → empty 200
    // -----------------------------------------------------------------------

    @Test
    void ac6a2_emailFilter_exactMatch_returns1Row() throws Exception {
        UUID ttId = UUID.randomUUID();
        seedGuest(CONCERT_A, "alice@example.com", "Alice Nguyen", ttId, "VIP");
        seedGuest(CONCERT_A, "bob@example.com", "Bob Tran", ttId, "VIP");

        String token = buildToken("svc-checkin-001", List.of("CHECKIN_STAFF"));

        mockMvc.perform(get("/internal/concerts/" + CONCERT_A + "/vip-guests")
                        .param("email", "alice@example.com")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].email").value("alice@example.com"))
                .andExpect(jsonPath("$.data.content[0].fullName").value("Alice Nguyen"));
    }

    @Test
    void ac6a2_emailFilter_caseInsensitive_uppercaseQuery_matches() throws Exception {
        // seed lowercase (as import would store), query UPPERCASE → service lowercases → still match
        UUID ttId = UUID.randomUUID();
        seedGuest(CONCERT_A, "alice@example.com", "Alice Nguyen", ttId, "VIP");

        String token = buildToken("svc-checkin-001", List.of("CHECKIN_STAFF"));

        mockMvc.perform(get("/internal/concerts/" + CONCERT_A + "/vip-guests")
                        .param("email", "ALICE@EXAMPLE.COM")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].email").value("alice@example.com"));
    }

    @Test
    void ac6a2_emailFilter_noMatch_returns200EmptyContent_not404() throws Exception {
        UUID ttId = UUID.randomUUID();
        seedGuest(CONCERT_A, "alice@example.com", "Alice Nguyen", ttId, "VIP");

        String token = buildToken("svc-checkin-001", List.of("CHECKIN_STAFF"));

        mockMvc.perform(get("/internal/concerts/" + CONCERT_A + "/vip-guests")
                        .param("email", "NONE@x.com")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())   // 200, NOT 404
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content.length()").value(0))
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    // -----------------------------------------------------------------------
    // AC6a-3: concert with no VIP guests → 200 empty page (not 404)
    // -----------------------------------------------------------------------

    @Test
    void ac6a3_concertWithNoVipGuests_returns200EmptyPage_not404() throws Exception {
        UUID unknownConcertId = UUID.randomUUID(); // nothing seeded
        String token = buildToken("svc-checkin-001", List.of("CHECKIN_STAFF"));

        mockMvc.perform(get("/internal/concerts/" + unknownConcertId + "/vip-guests")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content.length()").value(0))
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    // -----------------------------------------------------------------------
    // AC6a-4: pagination — size=2→2 items/totalElements=3/totalPages=2; page=1→1 item
    // -----------------------------------------------------------------------

    @Test
    void ac6a4_pagination_size2_firstPage_returns2Items_totalElements3_totalPages2() throws Exception {
        UUID ttId = UUID.randomUUID();
        seedGuest(CONCERT_A, "alice@example.com", "Alice", ttId, "VIP");
        seedGuest(CONCERT_A, "bob@example.com", "Bob", ttId, "VIP");
        seedGuest(CONCERT_A, "carol@example.com", "Carol", ttId, "VIP");

        String token = buildToken("svc-checkin-001", List.of("CHECKIN_STAFF"));

        mockMvc.perform(get("/internal/concerts/" + CONCERT_A + "/vip-guests")
                        .param("size", "2")
                        .param("page", "0")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.totalPages").value(2));
    }

    @Test
    void ac6a4_pagination_size2_secondPage_returns1Item() throws Exception {
        UUID ttId = UUID.randomUUID();
        seedGuest(CONCERT_A, "alice@example.com", "Alice", ttId, "VIP");
        seedGuest(CONCERT_A, "bob@example.com", "Bob", ttId, "VIP");
        seedGuest(CONCERT_A, "carol@example.com", "Carol", ttId, "VIP");

        String token = buildToken("svc-checkin-001", List.of("CHECKIN_STAFF"));

        mockMvc.perform(get("/internal/concerts/" + CONCERT_A + "/vip-guests")
                        .param("size", "2")
                        .param("page", "1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.totalElements").value(3));
    }

    // -----------------------------------------------------------------------
    // AC6a-5: security
    //   5a: no token         → 401 UNAUTHORIZED
    //   5b: role AUDIENCE    → 403 FORBIDDEN
    //   5c: CHECKIN_STAFF    → 200
    //   5d: ADMIN            → 200
    // -----------------------------------------------------------------------

    @Test
    void ac6a5a_noToken_returns401() throws Exception {
        mockMvc.perform(get("/internal/concerts/" + CONCERT_A + "/vip-guests"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void ac6a5b_audienceRole_returns403() throws Exception {
        String token = buildToken("user-aud-001", List.of("AUDIENCE"));

        mockMvc.perform(get("/internal/concerts/" + CONCERT_A + "/vip-guests")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void ac6a5c_checkinStaffRole_returns200() throws Exception {
        String token = buildToken("staff-001", List.of("CHECKIN_STAFF"));

        mockMvc.perform(get("/internal/concerts/" + UUID.randomUUID() + "/vip-guests")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void ac6a5d_adminRole_returns200() throws Exception {
        String token = buildToken("admin-001", List.of("ADMIN"));

        mockMvc.perform(get("/internal/concerts/" + UUID.randomUUID() + "/vip-guests")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    // -----------------------------------------------------------------------
    // AC6a-6: no-PII-leak / field-minimal
    //   Parse raw JSON of 1 row → must contain EXACTLY {email, fullName, ticketTypeId, ticketTypeName}
    //   MUST NOT contain id / importJobId / createdAt / updatedAt
    // -----------------------------------------------------------------------

    @Test
    void ac6a6_responseRow_exposesExactly4Fields_noInternalFields() throws Exception {
        UUID ttId = UUID.randomUUID();
        seedGuest(CONCERT_A, "pii@example.com", "PII Person", ttId, "VIP-GOLD");

        String token = buildToken("svc-checkin-001", List.of("CHECKIN_STAFF"));

        MvcResult result = mockMvc.perform(get("/internal/concerts/" + CONCERT_A + "/vip-guests")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        JsonNode root = objectMapper.readTree(body);
        JsonNode row = root.path("data").path("content").get(0);

        // Must have correct values for the 4 allowed fields
        assertThat(row.path("email").asText()).isEqualTo("pii@example.com");
        assertThat(row.path("fullName").asText()).isEqualTo("PII Person");
        assertThat(row.path("ticketTypeId").asText()).isEqualTo(ttId.toString());
        assertThat(row.path("ticketTypeName").asText()).isEqualTo("VIP-GOLD");

        // Must NOT expose internal fields
        Set<String> forbidden = Set.of("id", "importJobId", "createdAt", "updatedAt");
        row.fieldNames().forEachRemaining(fieldName ->
            assertThat(forbidden)
                .as("Response row must not expose internal field: " + fieldName)
                .doesNotContain(fieldName)
        );
    }
}
