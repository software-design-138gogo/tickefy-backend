package com.tickefy.csvingestion.modules.csvimport;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import com.github.tomakehurst.wiremock.client.WireMock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.tickefy.csvingestion.modules.csvimport.repository.ImportJobRepository;
import com.tickefy.csvingestion.modules.csvimport.storage.ObjectStorageClient;
import io.jsonwebtoken.Jwts;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * T-csv-3b-1: Upload endpoint integration tests.
 *
 * <p>AC map:
 * <ul>
 *   <li>AC-202: happy path organizer → 202 + importJobId + DB row PENDING + MinIO object exists.</li>
 *   <li>AC9-admin: ADMIN token bypasses ownership check → 202.</li>
 *   <li>AC9-wrong-owner: organizer sub != concert.organizerId → 403 FORBIDDEN.</li>
 *   <li>AC7: file &gt;10MB → 413 FILE_TOO_LARGE.</li>
 *   <li>AC-bad-header: CSV header != "name,email,ticket_type" → 400 INVALID_FILE_FORMAT.</li>
 *   <li>AC-encoding: non-UTF-8 bytes → 400 INVALID_ENCODING.</li>
 *   <li>AC-no-token: no Authorization header → 401 UNAUTHORIZED.</li>
 *   <li>AC-audience: AUDIENCE role → 403 FORBIDDEN (@PreAuthorize).</li>
 *   <li>AC-concert-not-found: WireMock 404 → 404 CONCERT_NOT_FOUND.</li>
 *   <li>AC-event-down: WireMock 500 → 503 SERVICE_UNAVAILABLE.</li>
 *   <li>AC-route-404: GET /api/admin/nope → 404 RESOURCE_NOT_FOUND.</li>
 * </ul>
 *
 * <p>Infrastructure: Testcontainers Postgres + Flyway (real schema), Testcontainers MinIO,
 * WireMock (stub event-service), RSA keypair generated at runtime.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {"app.messaging.outbox.enabled=false", "app.csv.reaper.enabled=false"})
class UploadEndpointIntegrationTest {

    // -----------------------------------------------------------------------
    // Shared IDs
    // -----------------------------------------------------------------------

    static final UUID ORG_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID OTHER_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    static final UUID CONCERT_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    static final String BUCKET = "tickefy-csv";
    static final String MINIO_IMAGE = "minio/minio:RELEASE.2024-01-16T16-07-38Z";

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
    // Testcontainers — MinIO
    // -----------------------------------------------------------------------

    @Container
    static final MinIOContainer MINIO =
            new MinIOContainer(MINIO_IMAGE)
                    .withUserName("minioadmin")
                    .withPassword("minioadmin");

    // -----------------------------------------------------------------------
    // WireMock (event-service stub) — static lifecycle
    // -----------------------------------------------------------------------

    static WireMockServer wireMock;

    @BeforeAll
    static void startWireMock() throws Exception {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();

        // Pre-create MinIO bucket so tests don't fail due to missing bucket
        MinioClient minioClient = MinioClient.builder()
                .endpoint(MINIO.getS3URL())
                .credentials(MINIO.getUserName(), MINIO.getPassword())
                .build();
        boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build());
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        }
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    @BeforeEach
    void resetWireMock() {
        wireMock.resetAll();
    }

    // -----------------------------------------------------------------------
    // RSA keypair generated at runtime — no private key committed
    // -----------------------------------------------------------------------

    static KeyPair PRIMARY_PAIR;
    static String publicKeyFilePath;

    static {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            PRIMARY_PAIR = gen.generateKeyPair();

            Path tmp = Files.createTempFile("jwt-upload-test-public-", ".pem");
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

    // -----------------------------------------------------------------------
    // @DynamicPropertySource — wire all infra together
    // -----------------------------------------------------------------------

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        // Postgres — override H2 from application-test.yml
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

        // MinIO
        registry.add("app.object-storage.endpoint", MINIO::getS3URL);
        registry.add("app.object-storage.access-key", MINIO::getUserName);
        registry.add("app.object-storage.secret-key", MINIO::getPassword);
        registry.add("app.object-storage.region", () -> "us-east-1");
        registry.add("app.object-storage.bucket", () -> BUCKET);

        // WireMock (event-service)
        registry.add("app.event.base-url", () -> "http://localhost:" + wireMock.port());

        // Shrink CB window so it opens quickly in tests
        registry.add("resilience4j.circuitbreaker.instances.event-service.sliding-window-size", () -> "3");
        registry.add("resilience4j.circuitbreaker.instances.event-service.minimum-number-of-calls", () -> "3");
        registry.add("resilience4j.circuitbreaker.instances.event-service.failure-rate-threshold", () -> "100");
        registry.add("resilience4j.circuitbreaker.instances.event-service.wait-duration-in-open-state", () -> "60s");

        // JWT public key — runtime-generated
        registry.add("app.jwt.public-key", () -> publicKeyFilePath);
        registry.add("app.jwt.issuer", () -> "tickefy-auth-service");
    }

    // -----------------------------------------------------------------------
    // Spring beans
    // -----------------------------------------------------------------------

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ImportJobRepository importJobRepository;

    @Autowired
    ObjectStorageClient objectStorageClient;

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Build a signed JWT RS256 with the given sub and roles. */
    private static String buildToken(String sub, List<String> roles) {
        return Jwts.builder()
                .subject(sub)
                .issuer("tickefy-auth-service")
                .claim("roles", roles)
                .expiration(new Date(System.currentTimeMillis() + 3_600_000L))
                .signWith(PRIMARY_PAIR.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }

    /** Stub WireMock to return 200 with a concert owned by the given organizerId. */
    private void stubConcertOk(UUID concertId, UUID organizerId) {
        wireMock.stubFor(
                WireMock.get(urlPathEqualTo("/internal/concerts/" + concertId))
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody("""
                                        {
                                          "success": true,
                                          "data": {
                                            "id": "%s",
                                            "organizerId": "%s",
                                            "status": "PUBLISHED"
                                          },
                                          "error": null,
                                          "requestId": "req-test"
                                        }
                                        """.formatted(concertId, organizerId))));
    }

    /** Build a minimal valid CSV multipart file. */
    private MockMultipartFile validCsvFile() {
        byte[] csvBytes = "name,email,ticket_type\nAlice,alice@example.com,VIP\n"
                .getBytes(StandardCharsets.UTF_8);
        return new MockMultipartFile("file", "valid.csv", "text/csv", csvBytes);
    }

    // -----------------------------------------------------------------------
    // AC-202: happy path — organizer owns concert → 202 + DB row + MinIO object
    // -----------------------------------------------------------------------

    @Test
    void ac202_organizerHappyPath_returns202_dbRow_minioObject() throws Exception {
        stubConcertOk(CONCERT_ID, ORG_ID);
        String token = buildToken(ORG_ID.toString(), List.of("ORGANIZER"));

        String responseBody = mockMvc.perform(multipart("/api/admin/csv-import")
                        .file(validCsvFile())
                        .param("concertId", CONCERT_ID.toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.importJobId").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // Extract importJobId from response
        // Quick JSON parse via Jackson ObjectMapper embedded in context is not injected here —
        // use simple string extraction (reliable for a single UUID field).
        String importJobIdStr = responseBody
                .replaceAll(".*\"importJobId\"\\s*:\\s*\"([^\"]+)\".*", "$1");
        UUID importJobId = UUID.fromString(importJobIdStr);

        // Verify DB row
        var job = importJobRepository.findById(importJobId);
        assertThat(job).as("import_jobs row must exist").isPresent();
        assertThat(job.get().getStatus()).isEqualTo("PENDING");
        assertThat(job.get().getConcertId()).isEqualTo(CONCERT_ID);
        assertThat(job.get().getOrganizerId()).isEqualTo(ORG_ID);
        assertThat(job.get().getObjectKey()).startsWith("csv-imports/");

        // Verify MinIO object
        String objectKey = job.get().getObjectKey();
        assertThat(objectStorageClient.exists(objectKey))
                .as("MinIO object must exist after upload: " + objectKey)
                .isTrue();
    }

    // -----------------------------------------------------------------------
    // AC9-admin: ADMIN bypasses ownership (sub != concert.organizerId) → 202
    // -----------------------------------------------------------------------

    @Test
    void ac9Admin_adminBypassesOwnership_returns202() throws Exception {
        // concert owned by ORG_ID but token sub = OTHER_ID with role ADMIN
        stubConcertOk(CONCERT_ID, ORG_ID);
        String token = buildToken(OTHER_ID.toString(), List.of("ADMIN"));

        mockMvc.perform(multipart("/api/admin/csv-import")
                        .file(validCsvFile())
                        .param("concertId", CONCERT_ID.toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.importJobId").isNotEmpty());
    }

    // -----------------------------------------------------------------------
    // AC9-wrong-owner: organizer does NOT own concert → 403 FORBIDDEN
    // -----------------------------------------------------------------------

    @Test
    void ac9WrongOwner_organizerDoesNotOwnConcert_returns403() throws Exception {
        // concert owned by ORG_ID but token sub = OTHER_ID with role ORGANIZER
        stubConcertOk(CONCERT_ID, ORG_ID);
        String token = buildToken(OTHER_ID.toString(), List.of("ORGANIZER"));

        mockMvc.perform(multipart("/api/admin/csv-import")
                        .file(validCsvFile())
                        .param("concertId", CONCERT_ID.toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    // -----------------------------------------------------------------------
    // AC7: file > 10MB → 413 FILE_TOO_LARGE
    // -----------------------------------------------------------------------

    @Test
    @Disabled("MockMvc MockMultipartFile bypasses Tomcat multipart parsing, so the servlet-layer "
            + "10MB limit (MaxUploadSizeExceededException -> 413, PLAN §B container-level) never fires "
            + "in webEnvironment=MOCK. 413 is verified against the real container via runtime curl (PLAN §K). "
            + "Production behaviour is correct; this assertion is not exercisable in MOCK mode.")
    void ac7_fileExceeds10MB_returns413() throws Exception {
        // 11 MB of zeros with a .csv extension — servlet layer catches MaxUploadSizeExceededException
        byte[] bigBytes = new byte[11 * 1024 * 1024];
        Arrays.fill(bigBytes, (byte) 'A');
        MockMultipartFile bigFile = new MockMultipartFile("file", "big.csv", "text/csv", bigBytes);
        String token = buildToken(ORG_ID.toString(), List.of("ORGANIZER"));

        mockMvc.perform(multipart("/api/admin/csv-import")
                        .file(bigFile)
                        .param("concertId", CONCERT_ID.toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error.code").value("FILE_TOO_LARGE"));
    }

    // -----------------------------------------------------------------------
    // AC-bad-header: CSV with wrong header → 400 INVALID_FILE_FORMAT
    // -----------------------------------------------------------------------

    @Test
    void acBadHeader_wrongCsvHeader_returns400InvalidFileFormat() throws Exception {
        // Do NOT stub WireMock — validator rejects before reaching EventClient
        byte[] csvBytes = "foo,bar\nAlice,alice@example.com\n".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile badHeaderFile = new MockMultipartFile("file", "bad.csv", "text/csv", csvBytes);
        String token = buildToken(ORG_ID.toString(), List.of("ORGANIZER"));

        mockMvc.perform(multipart("/api/admin/csv-import")
                        .file(badHeaderFile)
                        .param("concertId", CONCERT_ID.toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_FILE_FORMAT"));
    }

    // -----------------------------------------------------------------------
    // AC-encoding: non-UTF-8 bytes → 400 INVALID_ENCODING
    // -----------------------------------------------------------------------

    @Test
    void acEncoding_nonUtf8Bytes_returns400InvalidEncoding() throws Exception {
        // ISO-8859-1 byte 0xC3 followed by 0x28 (invalid UTF-8 2-byte sequence)
        // Prepend correct header as raw ASCII (all < 0x80), then inject bad bytes.
        byte[] header = "name,email,ticket_type\n".getBytes(StandardCharsets.US_ASCII);
        byte[] badData = new byte[]{(byte) 0xC3, (byte) 0x28}; // invalid UTF-8
        byte[] combined = new byte[header.length + badData.length];
        System.arraycopy(header, 0, combined, 0, header.length);
        System.arraycopy(badData, 0, combined, header.length, badData.length);

        MockMultipartFile badEncodingFile =
                new MockMultipartFile("file", "bad-enc.csv", "text/csv", combined);
        String token = buildToken(ORG_ID.toString(), List.of("ORGANIZER"));

        mockMvc.perform(multipart("/api/admin/csv-import")
                        .file(badEncodingFile)
                        .param("concertId", CONCERT_ID.toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_ENCODING"));
    }

    // -----------------------------------------------------------------------
    // AC-no-token: missing Authorization header → 401 UNAUTHORIZED
    // -----------------------------------------------------------------------

    @Test
    void acNoToken_missingAuthHeader_returns401() throws Exception {
        mockMvc.perform(multipart("/api/admin/csv-import")
                        .file(validCsvFile())
                        .param("concertId", CONCERT_ID.toString()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    // -----------------------------------------------------------------------
    // AC-audience: AUDIENCE role → 403 FORBIDDEN (@PreAuthorize)
    // -----------------------------------------------------------------------

    @Test
    void acAudience_audienceRoleBlocked_returns403() throws Exception {
        String token = buildToken(ORG_ID.toString(), List.of("AUDIENCE"));

        mockMvc.perform(multipart("/api/admin/csv-import")
                        .file(validCsvFile())
                        .param("concertId", CONCERT_ID.toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    // -----------------------------------------------------------------------
    // AC-concert-not-found: WireMock 404 → 404 CONCERT_NOT_FOUND
    // -----------------------------------------------------------------------

    @Test
    void acConcertNotFound_eventService404_returns404() throws Exception {
        wireMock.stubFor(
                WireMock.get(urlPathEqualTo("/internal/concerts/" + CONCERT_ID))
                        .willReturn(aResponse()
                                .withStatus(404)
                                .withHeader("Content-Type", "application/json")
                                .withBody("""
                                        {
                                          "success": false,
                                          "data": null,
                                          "error": {"code": "CONCERT_NOT_FOUND", "message": "not found"},
                                          "requestId": "req-404"
                                        }
                                        """)));
        String token = buildToken(ORG_ID.toString(), List.of("ORGANIZER"));

        mockMvc.perform(multipart("/api/admin/csv-import")
                        .file(validCsvFile())
                        .param("concertId", CONCERT_ID.toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("CONCERT_NOT_FOUND"));
    }

    // -----------------------------------------------------------------------
    // AC-event-down: WireMock 500 (≥ min-calls=3 to open CB) → 503 SERVICE_UNAVAILABLE
    // -----------------------------------------------------------------------

    @Test
    void acEventDown_eventService5xx_returns503() throws Exception {
        wireMock.stubFor(
                WireMock.get(urlPathEqualTo("/internal/concerts/" + CONCERT_ID))
                        .willReturn(aResponse()
                                .withStatus(500)
                                .withHeader("Content-Type", "application/json")
                                .withBody("{\"success\":false,\"error\":{\"code\":\"INTERNAL\"}}")));
        String token = buildToken(ORG_ID.toString(), List.of("ORGANIZER"));

        // Fire the request — even a single 5xx response from event-service must produce 503
        // (EventUnavailableException is mapped to SERVICE_UNAVAILABLE 503 by GlobalExceptionHandler).
        // We do NOT need CB to be OPEN; the single infra failure already throws 503.
        mockMvc.perform(multipart("/api/admin/csv-import")
                        .file(validCsvFile())
                        .param("concertId", CONCERT_ID.toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"));
    }

    // -----------------------------------------------------------------------
    // AC-route-404: GET /api/admin/nope → 404 RESOURCE_NOT_FOUND
    // -----------------------------------------------------------------------

    @Test
    void acRoute404_unknownRoute_returns404ResourceNotFound() throws Exception {
        String token = buildToken(ORG_ID.toString(), List.of("ORGANIZER"));

        mockMvc.perform(get("/api/admin/nope")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }
}
