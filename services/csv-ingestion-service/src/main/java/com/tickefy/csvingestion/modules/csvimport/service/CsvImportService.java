package com.tickefy.csvingestion.modules.csvimport.service;

import com.tickefy.csvingestion.common.exception.ApiException;
import com.tickefy.csvingestion.common.exception.ErrorCode;
import com.tickefy.csvingestion.modules.csvimport.client.ConcertSummary;
import com.tickefy.csvingestion.modules.csvimport.client.EventClient;
import com.tickefy.csvingestion.modules.csvimport.storage.ObjectStorageClient;
import com.tickefy.csvingestion.modules.csvimport.validation.CsvFileValidator;
import com.tickefy.csvingestion.modules.csvimport.validation.CsvFileValidator.ValidatedCsv;
import java.io.ByteArrayInputStream;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class CsvImportService {

    private static final Logger log = LoggerFactory.getLogger(CsvImportService.class);

    private final CsvFileValidator fileValidator;
    private final EventClient eventClient;
    private final ObjectStorageClient objectStorage;
    private final CsvImportPersistence persistence;

    public CsvImportService(
            CsvFileValidator fileValidator,
            EventClient eventClient,
            ObjectStorageClient objectStorage,
            CsvImportPersistence persistence) {
        this.fileValidator = fileValidator;
        this.eventClient = eventClient;
        this.objectStorage = objectStorage;
        this.persistence = persistence;
    }

    /**
     * Validate file, verify concert + organizer ownership, store the raw CSV, then create a PENDING
     * import job. HTTP (event) and object-storage calls run OUTSIDE the DB transaction (§8); the
     * object is stored BEFORE the row is inserted because object_key is NOT NULL.
     */
    public UUID createImportJob(
            MultipartFile file, UUID concertId, String sub, boolean isAdmin, String bearerToken) {
        // 1. File-level validation first — reject before any concert lookup / storage / job.
        ValidatedCsv validated = fileValidator.validate(file);

        // 2. Concert existence + organizer ownership (ADMIN bypasses ownership).
        ConcertSummary concert = eventClient.getConcert(concertId, bearerToken);
        UUID uploader = UUID.fromString(sub);
        if (!isAdmin && !uploader.equals(concert.organizerId())) {
            throw new ApiException(
                    ErrorCode.FORBIDDEN, "Organizer does not own this concert", org.springframework.http.HttpStatus.FORBIDDEN);
        }

        // 3. Store raw CSV under a server-generated key (no client filename -> no path traversal).
        String objectKey = "csv-imports/" + UUID.randomUUID() + ".csv";
        byte[] bytes = validated.bytes();
        objectStorage.putObject(
                objectKey, new ByteArrayInputStream(bytes), bytes.length, "text/csv");

        // 4. Create PENDING job (short TX, after storage succeeded).
        UUID importJobId = persistence.createJob(concertId, uploader, objectKey);
        log.info("Import job created importJobId={} concertId={}", importJobId, concertId);
        return importJobId;
    }
}
