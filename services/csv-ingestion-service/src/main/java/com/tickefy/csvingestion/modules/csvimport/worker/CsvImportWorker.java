package com.tickefy.csvingestion.modules.csvimport.worker;

import com.tickefy.csvingestion.common.exception.ApiException;
import com.tickefy.csvingestion.modules.csvimport.entity.ImportErrorEntity;
import com.tickefy.csvingestion.modules.csvimport.entity.ImportJobEntity;
import com.tickefy.csvingestion.modules.csvimport.entity.VipGuestStagingEntity;
import com.tickefy.csvingestion.modules.csvimport.repository.ImportJobRepository;
import com.tickefy.csvingestion.modules.csvimport.resolver.TicketTypeMap;
import com.tickefy.csvingestion.modules.csvimport.resolver.TicketTypeResolver;
import com.tickefy.csvingestion.modules.csvimport.service.CsvImportPersistence;
import com.tickefy.csvingestion.modules.csvimport.storage.ObjectStorageClient;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Async CSV ingest worker: claim job, stream-parse the stored CSV, validate + resolve + dedup each
 * row, and stage valid rows / record per-row errors. Stops at PROCESSING — threshold, promote to
 * vip_guests, terminal status and event publish are T4c/T5. Bean is separate so @Async proxy and
 * the resolver/persistence cross-bean calls apply (§8). Do NOT self-invoke {@code process}.
 */
@Component
public class CsvImportWorker {

    private static final Logger log = LoggerFactory.getLogger(CsvImportWorker.class);

    /** RFC-lite email check (PLAN R5). */
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private static final String COL_NAME = "name";
    private static final String COL_EMAIL = "email";
    private static final String COL_TICKET_TYPE = "ticket_type";

    private final ImportJobRepository jobRepository;
    private final CsvImportPersistence persistence;
    private final TicketTypeResolver ticketTypeResolver;
    private final ObjectStorageClient objectStorage;
    private final int batchSize;

    public CsvImportWorker(
            ImportJobRepository jobRepository,
            CsvImportPersistence persistence,
            TicketTypeResolver ticketTypeResolver,
            ObjectStorageClient objectStorage,
            @Value("${app.csv.batch-size:1000}") int batchSize) {
        this.jobRepository = jobRepository;
        this.persistence = persistence;
        this.ticketTypeResolver = ticketTypeResolver;
        this.objectStorage = objectStorage;
        this.batchSize = batchSize;
    }

    @Async("csvWorkerExecutor")
    public void process(UUID jobId) {
        if (!persistence.claimJob(jobId)) {
            log.info("Skip ingest: job not PENDING jobId={}", jobId);
            return;
        }
        ImportJobEntity job = jobRepository.findById(jobId).orElseThrow();
        UUID concertId = job.getConcertId();

        TicketTypeMap ticketTypes;
        try {
            ticketTypes = ticketTypeResolver.loadForConcert(concertId);
        } catch (ApiException e) {
            // Inventory unavailable (CB 503): leave job PROCESSING for T4c retry/terminal handling.
            log.warn("Ingest aborted (inventory unavailable) jobId={} status=PROCESSING", jobId);
            return;
        }

        List<VipGuestStagingEntity> stagingBuf = new ArrayList<>();
        List<ImportErrorEntity> errorBuf = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int total = 0;
        int staged = 0;
        int failed = 0;
        int duplicate = 0;

        try (InputStream in = objectStorage.getObject(job.getObjectKey());
                Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
                CSVParser parser = CSVFormat.DEFAULT
                        .builder()
                        .setHeader()
                        .setSkipHeaderRecord(true)
                        .setTrim(true)
                        .build()
                        .parse(reader)) {

            for (CSVRecord record : parser) {
                total++;
                long lineNumber = record.getRecordNumber() + 1; // header = line 1, first data = line 2

                String name = getOrNull(record, COL_NAME);
                String email = getOrNull(record, COL_EMAIL);
                String ticketType = getOrNull(record, COL_TICKET_TYPE);

                if (isBlank(name) || isBlank(email) || isBlank(ticketType)) {
                    errorBuf.add(error(jobId, lineNumber, record, "MISSING_FIELD"));
                    failed++;
                } else if (!EMAIL.matcher(email.trim()).matches()) {
                    errorBuf.add(error(jobId, lineNumber, record, "INVALID_EMAIL"));
                    failed++;
                } else {
                    Optional<UUID> ticketTypeId = ticketTypes.resolve(ticketType);
                    if (ticketTypeId.isEmpty()) {
                        errorBuf.add(error(jobId, lineNumber, record, "TICKET_TYPE_NOT_FOUND"));
                        failed++;
                    } else {
                        String emailNorm = email.trim().toLowerCase(Locale.ROOT);
                        if (!seen.add(emailNorm)) {
                            errorBuf.add(error(jobId, lineNumber, record, "DUPLICATE_ROW"));
                            duplicate++;
                        } else {
                            stagingBuf.add(VipGuestStagingEntity.builder()
                                    .importJobId(jobId)
                                    .concertId(concertId)
                                    .email(emailNorm)
                                    .fullName(name.trim())
                                    .ticketTypeId(ticketTypeId.get())
                                    .ticketTypeName(ticketType.trim())
                                    .lineNumber((int) lineNumber)
                                    .build());
                            staged++;
                        }
                    }
                }

                if (stagingBuf.size() >= batchSize) {
                    persistence.saveStagingBatch(stagingBuf);
                    stagingBuf = new ArrayList<>();
                }
                if (errorBuf.size() >= batchSize) {
                    persistence.saveErrorsBatch(errorBuf);
                    errorBuf = new ArrayList<>();
                }
            }
        } catch (ApiException e) {
            log.warn("Ingest aborted (object storage) jobId={} status=PROCESSING", jobId);
            return;
        } catch (Exception e) {
            // §15: do NOT log the throwable — a CSV parse exception message can embed the raw
            // offending row (PII). Log only the exception type.
            log.error("Ingest parse error jobId={} cause={}", jobId, e.getClass().getName());
            return;
        }

        persistence.saveStagingBatch(stagingBuf);
        persistence.saveErrorsBatch(errorBuf);
        persistence.updateCounters(jobId, total, staged, failed, duplicate);
        log.info(
                "Ingest done jobId={} total={} staged={} failed={} duplicate={} status=PROCESSING",
                jobId, total, staged, failed, duplicate);
    }

    private static String getOrNull(CSVRecord record, String column) {
        return record.isMapped(column) ? record.get(column) : null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** Build an error row; raw data truncated to the 1024-char column limit, never logged (§15). */
    private static ImportErrorEntity error(
            UUID jobId, long lineNumber, CSVRecord record, String reason) {
        String raw = String.join(",", record.toList());
        if (raw.length() > 1024) {
            raw = raw.substring(0, 1024);
        }
        return ImportErrorEntity.builder()
                .importJobId(jobId)
                .lineNumber((int) lineNumber)
                .rawData(raw)
                .reason(reason)
                .build();
    }
}
