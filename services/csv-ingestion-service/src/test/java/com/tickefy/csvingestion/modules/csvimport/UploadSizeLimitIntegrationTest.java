package com.tickefy.csvingestion.modules.csvimport;

import static org.assertj.core.api.Assertions.assertThat;

import io.jsonwebtoken.Jwts;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * FOLD AC7-413: Upload size-limit test using RANDOM_PORT (real Tomcat).
 *
 * <p>Purpose: AC7 in UploadEndpointIntegrationTest is @Disabled because MockMvc (MOCK mode)
 * bypasses Tomcat multipart-limit enforcement. RANDOM_PORT starts a real embedded Tomcat server
 * which DOES enforce max-file-size=10MB / max-request-size=11MB from application.yml.
 *
 * <p>Uses raw {@link HttpURLConnection} (not JDK HttpClient / TestRestTemplate) because JDK
 * HttpClient in Java 25 cannot handle Tomcat's RST mid-streaming-upload; HttpURLConnection
 * is more tolerant and can read the 413 error response after Tomcat resets the connection.
 *
 * <p>Expected behaviour: POST /api/admin/csv-import with an 11MB payload → Tomcat throws
 * MaxUploadSizeExceededException → GlobalExceptionHandler maps to 413 FILE_TOO_LARGE.
 *
 * <p>Bug indicator: If status is 500 (INTERNAL_SERVER_ERROR), MaxUploadSizeExceededException
 * is thrown during multipart resolution BEFORE @RestControllerAdvice handles it (bug CF1).
 * In that case the test FAILS with actual status + body as evidence. DO NOT fix here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class UploadSizeLimitIntegrationTest {

    static final String BUCKET = "tickefy-csv";
    static final String MINIO_IMAGE = "minio/minio:RELEASE.2024-01-16T16-07-38Z";

    // -----------------------------------------------------------------------
    // Testcontainers — Postgres
    // -----------------------------------------------------------------------

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("csv_ingestion_test_sizetest")
                    .withUsername("csv_test")
                    .withPassword("csv_test");

    // -----------------------------------------------------------------------
    // Testcontainers — MinIO (needed because Spring context boots MinIO client bean)
    // -----------------------------------------------------------------------

    @Container
    static final MinIOContainer MINIO =
            new MinIOContainer(MINIO_IMAGE)
                    .withUserName("minioadmin")
                    .withPassword("minioadmin");

    // -----------------------------------------------------------------------
    // RSA keypair generated at runtime
    // -----------------------------------------------------------------------

    static KeyPair PRIMARY_PAIR;
    static String publicKeyFilePath;

    static {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            PRIMARY_PAIR = gen.generateKeyPair();

            Path tmp = Files.createTempFile("jwt-sizetest-public-", ".pem");
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
    // @DynamicPropertySource
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

        // MinIO — real container
        registry.add("app.object-storage.endpoint", MINIO::getS3URL);
        registry.add("app.object-storage.access-key", MINIO::getUserName);
        registry.add("app.object-storage.secret-key", MINIO::getPassword);
        registry.add("app.object-storage.region", () -> "us-east-1");
        registry.add("app.object-storage.bucket", () -> BUCKET);

        // Event service — not called (file rejected at multipart layer before controller dispatch)
        registry.add("app.event.base-url", () -> "http://localhost:9998");

        // JWT public key — runtime-generated
        registry.add("app.jwt.public-key", () -> publicKeyFilePath);
        registry.add("app.jwt.issuer", () -> "tickefy-auth-service");
    }

    @BeforeAll
    static void createMinioBucket() throws Exception {
        MinioClient minioClient = MinioClient.builder()
                .endpoint(MINIO.getS3URL())
                .credentials(MINIO.getUserName(), MINIO.getPassword())
                .build();
        boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build());
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        }
    }

    // -----------------------------------------------------------------------
    // Local server port (injected after RANDOM_PORT assignment)
    // -----------------------------------------------------------------------

    @LocalServerPort
    int port;

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

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
    // AC7-413: FOLD — POST 11MB file with valid ORGANIZER token → 413 FILE_TOO_LARGE
    //
    // Uses raw HttpURLConnection to avoid JDK HttpClient chunked-encoding RST issue.
    // -----------------------------------------------------------------------

    @Test
    void ac7_fileExceeds10MB_realTomcat_returns413FileTooLarge() throws Exception {
        // 11 MB of 'A' bytes — Tomcat enforces max-file-size=10MB / max-request-size=11MB
        byte[] bigContent = new byte[11 * 1024 * 1024];
        Arrays.fill(bigContent, (byte) 'A');

        String token = buildToken("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", List.of("ORGANIZER"));

        // Build a minimal multipart/form-data body manually for exact byte control
        String boundary = "----TestBoundary" + UUID.randomUUID().toString().replace("-", "");
        String crlf = "\r\n";

        // Part 1: concertId field
        String concertIdPart = "--" + boundary + crlf
                + "Content-Disposition: form-data; name=\"concertId\"" + crlf
                + crlf
                + "cccccccc-cccc-cccc-cccc-cccccccccccc" + crlf;

        // Part 2: file part header (before big content)
        String filePartHeader = "--" + boundary + crlf
                + "Content-Disposition: form-data; name=\"file\"; filename=\"big.csv\"" + crlf
                + "Content-Type: text/csv" + crlf
                + crlf;

        // Closing boundary
        String closingBoundary = crlf + "--" + boundary + "--" + crlf;

        byte[] concertIdBytes = concertIdPart.getBytes(StandardCharsets.UTF_8);
        byte[] fileHeaderBytes = filePartHeader.getBytes(StandardCharsets.UTF_8);
        byte[] closingBytes = closingBoundary.getBytes(StandardCharsets.UTF_8);

        URI uri = URI.create("http://localhost:" + port + "/api/admin/csv-import");
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        conn.setRequestProperty("Authorization", "Bearer " + token);
        // Use chunked streaming (NOT fixed-length) so that HttpURLConnection can read the
        // 413 error response even when Tomcat RSTs before we finish writing all bytes.
        conn.setChunkedStreamingMode(64 * 1024);
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(30_000);

        // Write the body — Tomcat may RST mid-write; suppress IOException here and
        // read the response code afterwards (HttpURLConnection buffers the response).
        try (OutputStream out = conn.getOutputStream()) {
            out.write(concertIdBytes);
            out.write(fileHeaderBytes);
            // Write big content in 64KB chunks to allow Tomcat to RST early
            int chunkSize = 64 * 1024;
            int written = 0;
            while (written < bigContent.length) {
                int toWrite = Math.min(chunkSize, bigContent.length - written);
                out.write(bigContent, written, toWrite);
                written += toWrite;
            }
            out.write(closingBytes);
        } catch (IOException writeEx) {
            // Tomcat RST the connection mid-write because the upload exceeded the limit.
            // HttpURLConnection will still allow us to read the response status below.
        }

        int actualStatus;
        try {
            actualStatus = conn.getResponseCode();
        } catch (IOException e) {
            // Cannot even read a status code — total connection abort without response.
            // This means Tomcat RST without sending HTTP response (infrastructure issue or bug CF1).
            throw new AssertionError(
                    "AC7-413 RED: Could not read HTTP response code after sending 11MB payload. "
                    + "Tomcat aborted connection without sending a response. "
                    + "Possible bug CF1: MaxUploadSizeExceededException thrown before "
                    + "@RestControllerAdvice handles it. Root cause: " + e.getMessage()
                    + ". Report to main agent.", e);
        }

        String responseBody;
        try {
            InputStream errStream = conn.getErrorStream();
            responseBody = errStream != null
                    ? new String(errStream.readAllBytes(), StandardCharsets.UTF_8)
                    : "(no error stream)";
        } catch (IOException e) {
            responseBody = "(error reading body: " + e.getMessage() + ")";
        }

        assertThat(actualStatus)
                .as("AC7-413: Expected 413 FILE_TOO_LARGE from real Tomcat (max-file-size=10MB). "
                        + "Actual=%d, body=%s. "
                        + "If 500: bug CF1 — MaxUploadSizeExceededException not reaching @RestControllerAdvice. "
                        + "Report to main agent, do NOT hack.",
                        actualStatus, responseBody)
                .isEqualTo(413);

        assertThat(responseBody)
                .as("Response body must contain FILE_TOO_LARGE error code. body=%s", responseBody)
                .contains("FILE_TOO_LARGE");
    }
}
