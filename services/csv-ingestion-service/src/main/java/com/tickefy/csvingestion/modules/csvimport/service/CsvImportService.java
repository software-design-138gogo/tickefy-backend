package com.tickefy.csvingestion.modules.csvimport.service;

import com.tickefy.csvingestion.common.exception.ApiException;
import com.tickefy.csvingestion.common.exception.ErrorCode;
import com.tickefy.csvingestion.modules.csvimport.client.ConcertSummary;
import com.tickefy.csvingestion.modules.csvimport.client.EventClient;
import com.tickefy.csvingestion.modules.csvimport.dto.CsvImportRetryResponse;
import com.tickefy.csvingestion.modules.csvimport.dto.CsvImportStatusResponse;
import com.tickefy.csvingestion.modules.csvimport.entity.ImportJobEntity;
import com.tickefy.csvingestion.modules.csvimport.mapper.CsvImportMapper;
import com.tickefy.csvingestion.modules.csvimport.repository.ImportErrorRepository;
import com.tickefy.csvingestion.modules.csvimport.repository.ImportJobRepository;
import com.tickefy.csvingestion.modules.csvimport.storage.ObjectStorageClient;
import com.tickefy.csvingestion.modules.csvimport.validation.CsvFileValidator;
import com.tickefy.csvingestion.modules.csvimport.validation.CsvFileValidator.ValidatedCsv;
import com.tickefy.csvingestion.modules.csvimport.worker.WorkerTrigger;
import java.io.ByteArrayInputStream;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class CsvImportService {

    private static final Logger log = LoggerFactory.getLogger(CsvImportService.class);

    private final CsvFileValidator fileValidator;
    private final EventClient eventClient;
    private final ObjectStorageClient objectStorage;
    private final CsvImportPersistence persistence;
    private final ImportJobRepository importJobRepository;
    private final ImportErrorRepository importErrorRepository;
    private final CsvImportMapper mapper;
    private final ObjectProvider<WorkerTrigger> workerTrigger;

    public CsvImportService(
            CsvFileValidator fileValidator,
            EventClient eventClient,
            ObjectStorageClient objectStorage,
            CsvImportPersistence persistence,
            ImportJobRepository importJobRepository,
            ImportErrorRepository importErrorRepository,
            CsvImportMapper mapper,
            ObjectProvider<WorkerTrigger> workerTrigger) {
        this.fileValidator = fileValidator;
        this.eventClient = eventClient;
        this.objectStorage = objectStorage;
        this.persistence = persistence;
        this.importJobRepository = importJobRepository;
        this.importErrorRepository = importErrorRepository;
        this.mapper = mapper;
        this.workerTrigger = workerTrigger;
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

        // 5. Fire async ingest worker after the job row is committed (gated; absent in tests).
        workerTrigger.ifAvailable(t -> t.trigger(importJobId));
        return importJobId;
    }

    /** Job status + summary + error rows. 404 if missing, 403 if caller is not owner/admin. */
    public CsvImportStatusResponse getStatus(UUID jobId, String sub, boolean isAdmin) {
        ImportJobEntity job = findOwnedJob(jobId, sub, isAdmin);
        return mapper.toStatusResponse(job, importErrorRepository.findByImportJobId(jobId));
    }

    /** Retry a FAILED job (state-guard §6.9): clear staging + flip PENDING. 422 if not FAILED. */
    public CsvImportRetryResponse retry(UUID jobId, String sub, boolean isAdmin) {
        ImportJobEntity job = findOwnedJob(jobId, sub, isAdmin);
        if (!"FAILED".equals(job.getStatus())) {
            throw new ApiException(
                    ErrorCode.IMPORT_JOB_NOT_RETRYABLE,
                    "Only FAILED jobs can be retried",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        persistence.resetForRetry(jobId);
        return new CsvImportRetryResponse(jobId, "PENDING");
    }

    private ImportJobEntity findOwnedJob(UUID jobId, String sub, boolean isAdmin) {
        ImportJobEntity job = importJobRepository
                .findById(jobId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.IMPORT_JOB_NOT_FOUND, "Import job not found", HttpStatus.NOT_FOUND));
        if (!isAdmin && !job.getOrganizerId().equals(UUID.fromString(sub))) {
            throw new ApiException(
                    ErrorCode.FORBIDDEN, "Access denied to this import job", HttpStatus.FORBIDDEN);
        }
        return job;
    }
}
