package com.tickefy.csvingestion.modules.csvimport.storage;

import static org.assertj.core.api.Assertions.assertThat;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * T-csv-3a-2: ObjectStorageClient integration test against real MinIO (Testcontainers).
 *
 * <p>Does NOT need Spring context — MinioObjectStorageClient is a pure wrapper.
 *
 * <p>AC coverage:
 * <ul>
 *   <li>AC-put-exists: putObject succeeds → exists returns true.</li>
 *   <li>AC-roundtrip: getObject returns bytes identical to what was put.</li>
 *   <li>AC-not-exists: exists("nope/x") returns false for absent key.</li>
 * </ul>
 */
@Testcontainers
class ObjectStorageClientIntegrationTest {

    private static final String BUCKET = "tickefy-csv";
    private static final String MINIO_IMAGE = "minio/minio:RELEASE.2024-01-16T16-07-38Z";

    @Container
    static final MinIOContainer minioContainer =
            new MinIOContainer(MINIO_IMAGE)
                    .withUserName("minioadmin")
                    .withPassword("minioadmin");

    static MinioObjectStorageClient storageClient;

    @BeforeAll
    static void setUp() throws Exception {
        MinioClient minioClient = MinioClient.builder()
                .endpoint(minioContainer.getS3URL())
                .credentials(minioContainer.getUserName(), minioContainer.getPassword())
                .build();

        // Create bucket
        boolean exists = minioClient.bucketExists(
                BucketExistsArgs.builder().bucket(BUCKET).build());
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        }

        storageClient = new MinioObjectStorageClient(minioClient, BUCKET);
    }

    // -----------------------------------------------------------------------
    // AC-put-exists: putObject → exists == true
    // -----------------------------------------------------------------------

    @Test
    void acPutExists_putObject_thenExistsTrue() {
        byte[] content = "name,email\nAlice,alice@example.com\n".getBytes(StandardCharsets.UTF_8);
        String key = "test/sample.csv";

        storageClient.putObject(key, new ByteArrayInputStream(content), content.length, "text/csv");

        assertThat(storageClient.exists(key))
                .as("exists() must return true after putObject")
                .isTrue();
    }

    // -----------------------------------------------------------------------
    // AC-roundtrip: putObject → getObject bytes match input
    // -----------------------------------------------------------------------

    @Test
    void acRoundtrip_getObject_bytesMatchInput() throws Exception {
        byte[] content =
                "id,name\n1,Concert A\n2,Concert B\n".getBytes(StandardCharsets.UTF_8);
        String key = "test/roundtrip.csv";

        storageClient.putObject(key, new ByteArrayInputStream(content), content.length, "text/csv");

        InputStream retrieved = storageClient.getObject(key);
        byte[] retrievedBytes = retrieved.readAllBytes();

        assertThat(retrievedBytes)
                .as("getObject must return bytes identical to those put")
                .isEqualTo(content);
    }

    // -----------------------------------------------------------------------
    // AC-not-exists: exists for absent key == false
    // -----------------------------------------------------------------------

    @Test
    void acNotExists_absentKey_existsFalse() {
        assertThat(storageClient.exists("nope/x.csv"))
                .as("exists() must return false for a key that was never put")
                .isFalse();
    }
}
