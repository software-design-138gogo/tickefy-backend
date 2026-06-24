package com.tickefy.csvingestion.modules.csvimport.worker;

import com.tickefy.csvingestion.common.exception.ApiException;
import com.tickefy.csvingestion.modules.csvimport.entity.ImportErrorEntity;
import com.tickefy.csvingestion.modules.csvimport.entity.ImportJobEntity;
import com.tickefy.csvingestion.modules.csvimport.entity.VipGuestStagingEntity;
import com.tickefy.csvingestion.modules.csvimport.repository.ImportErrorRepository;
import com.tickefy.csvingestion.modules.csvimport.repository.ImportJobRepository;
import com.tickefy.csvingestion.modules.csvimport.resolver.TicketTypeMap;
import com.tickefy.csvingestion.modules.csvimport.resolver.TicketTypeResolver;
import com.tickefy.csvingestion.modules.csvimport.service.CsvImportPersistence;
import com.tickefy.csvingestion.modules.csvimport.storage.ObjectStorageClient;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringWriter;
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
import org.apache.commons.csv.CSVPrinter;
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
    private final ImportErrorRepository errorRepository;
    private final CsvImportPersistence persistence;
    private final TicketTypeResolver ticketTypeResolver;
    private final ObjectStorageClient objectStorage;
    private final int batchSize;
    private final double errorThreshold;

    public CsvImportWorker(
            ImportJobRepository jobRepository,
            ImportErrorRepository errorRepository,
            CsvImportPersistence persistence,
            TicketTypeResolver ticketTypeResolver,
            ObjectStorageClient objectStorage,
            @Value("${app.csv.batch-size:1000}") int batchSize,
            @Value("${app.csv.error-threshold:0.5}") double errorThreshold) {
        this.jobRepository = jobRepository;
        this.errorRepository = errorRepository;
        this.persistence = persistence;
        this.ticketTypeResolver = ticketTypeResolver;
        this.objectStorage = objectStorage;
        this.batchSize = batchSize;
        this.errorThreshold = errorThreshold;
    }

    @Async("csvWorkerExecutor")
    public void process(UUID jobId) {
        if (!persistence.claimJob(jobId)) {
            log.info("Skip ingest: job not PENDING jobId={}", jobId);
            return;
        }
        try {
            ImportJobEntity job = jobRepository.findById(jobId).orElseThrow();
            Counters counters = ingest(jobId, job);
            finalizeJob(jobId, counters);
        } catch (Exception e) {
            // §15: PII-free reason (exception type / error code), never the message/stacktrace.
            persistence.markFailed(jobId, safeReason(e));
            log.error("Ingest failed jobId={} cause={}", jobId, e.getClass().getName());
        }
    }

    /** Stream-parse + validate + resolve + dedup + stage. Exceptions propagate to the wrapper. */
    private Counters ingest(UUID jobId, ImportJobEntity job) throws Exception {
        UUID concertId = job.getConcertId();
        TicketTypeMap ticketTypes = ticketTypeResolver.loadForConcert(concertId);

        List<VipGuestStagingEntity> stagingBuf = new ArrayList<>();
        List<ImportErrorEntity> errorBuf = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Counters c = new Counters();

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
                c.total++;
                long lineNumber = record.getRecordNumber() + 1; // header = line 1, first data = line 2

                String name = getOrNull(record, COL_NAME);
                String email = getOrNull(record, COL_EMAIL);
                String ticketType = getOrNull(record, COL_TICKET_TYPE);

                if (isBlank(name) || isBlank(email) || isBlank(ticketType)) {
                    errorBuf.add(error(jobId, lineNumber, record, "MISSING_FIELD"));
                    c.failed++;
                } else if (!EMAIL.matcher(email.trim()).matches()) {
                    errorBuf.add(error(jobId, lineNumber, record, "INVALID_EMAIL"));
                    c.failed++;
                } else {
                    Optional<UUID> ticketTypeId = ticketTypes.resolve(ticketType);
                    if (ticketTypeId.isEmpty()) {
                        errorBuf.add(error(jobId, lineNumber, record, "TICKET_TYPE_NOT_FOUND"));
                        c.failed++;
                    } else {
                        String emailNorm = email.trim().toLowerCase(Locale.ROOT);
                        if (!seen.add(emailNorm)) {
                            errorBuf.add(error(jobId, lineNumber, record, "DUPLICATE_ROW"));
                            c.duplicate++;
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
                            c.staged++;
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
        }

        persistence.saveStagingBatch(stagingBuf);
        persistence.saveErrorsBatch(errorBuf);
        persistence.updateCounters(jobId, c.total, c.staged, c.failed, c.duplicate);
        return c;
    }

    /** Threshold -> promote (idempotent) -> error-report -> terminal status (state-guard §6.9). */
    private void finalizeJob(UUID jobId, Counters c) {
        int errorCount = c.failed + c.duplicate;
        String reportKey = errorCount > 0 ? uploadErrorReport(jobId) : null;

        String status;
        int success = 0;
        if (c.total == 0) {
            status = "COMPLETED"; // empty file (header only) — no rows, no divide-by-zero
        } else {
            double ratio = (double) errorCount / c.total;
            if (ratio > errorThreshold) {
                status = "FAILED"; // over threshold -> do NOT promote (spec §A4)
            } else {
                success = persistence.promote(jobId); // idempotent ON CONFLICT DO NOTHING
                status = errorCount == 0 ? "COMPLETED" : "PARTIALLY_COMPLETED";
            }
        }

        boolean applied = persistence.markTerminal(jobId, status, success, reportKey);
        log.info(
                "Finalize jobId={} status={} success={} errors={} total={} applied={}",
                jobId, status, success, errorCount, c.total, applied);
    }

    /** Build a CSV error report and store it; returns the object key. Content never logged (§15). */
    private String uploadErrorReport(UUID jobId) {
        List<ImportErrorEntity> errors = errorRepository.findByImportJobId(jobId);
        StringWriter sw = new StringWriter();
        try (CSVPrinter printer = new CSVPrinter(
                sw, CSVFormat.DEFAULT.builder().setHeader("line_number", "raw_data", "reason").build())) {
            for (ImportErrorEntity e : errors) {
                printer.printRecord(e.getLineNumber(), e.getRawData(), e.getReason());
            }
        } catch (Exception e) {
            throw new IllegalStateException("error-report build failed", e);
        }
        byte[] bytes = sw.toString().getBytes(StandardCharsets.UTF_8);
        String key = "error-reports/" + jobId + ".csv";
        objectStorage.putObject(key, new ByteArrayInputStream(bytes), bytes.length, "text/csv");
        return key;
    }

    /** PII-free failure reason (§15): error code for ApiException, else exception simple name. */
    private static String safeReason(Throwable e) {
        if (e instanceof ApiException api) {
            return api.getErrorCode().name();
        }
        return e.getClass().getSimpleName();
    }

    /** Mutable per-job counters. */
    private static final class Counters {
        int total;
        int staged;
        int failed;
        int duplicate;
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
