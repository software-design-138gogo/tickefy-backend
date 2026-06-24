package com.tickefy.csvingestion.modules.csvimport.worker;

import com.tickefy.csvingestion.modules.csvimport.service.CsvImportPersistence;
import com.tickefy.csvingestion.modules.csvimport.storage.ObjectStorageClient;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cron pickup: periodically scans MinIO {@code cron-inbox/{concertId}/*.csv} and creates a CRON-sourced
 * import job per new file, then triggers the async worker (reusing the upload ingest/finalize path).
 *
 * <p>System-initiated (no JWT): organizer_id is null and the concert is NOT validated — the
 * {@code cron-inbox/} prefix is a trusted drop-zone (private bucket, internal write only). The concertId
 * is parsed from the path and MUST be a UUID (§12); invalid paths are skipped, never used for file ops.
 * Dedup via existsByObjectKey (§6.9). Scan/create run OUTSIDE any TX (§8). Never logs CSV content (§15).
 */
@Component
@ConditionalOnProperty(name = "app.csv.scan.enabled", havingValue = "true", matchIfMissing = true)
public class CsvCronScanner {

    private static final Logger log = LoggerFactory.getLogger(CsvCronScanner.class);
    private static final String PREFIX = "cron-inbox/";

    private final ObjectStorageClient objectStorage;
    private final CsvImportPersistence persistence;
    private final ObjectProvider<WorkerTrigger> workerTrigger;

    public CsvCronScanner(
            ObjectStorageClient objectStorage,
            CsvImportPersistence persistence,
            ObjectProvider<WorkerTrigger> workerTrigger) {
        this.objectStorage = objectStorage;
        this.persistence = persistence;
        this.workerTrigger = workerTrigger;
    }

    @Scheduled(cron = "${app.csv.scan.cron:0 0 1 * * *}")
    public void scan() {
        List<String> keys = objectStorage.listObjects(PREFIX); // network — outside any TX (§8)
        for (String key : keys) {
            if (!key.toLowerCase().endsWith(".csv")) {
                continue; // ignore non-CSV junk in the drop-zone (no noise FAILED jobs)
            }
            UUID concertId = parseConcertId(key);
            if (concertId == null) {
                log.warn("Cron scan: skip invalid path key={}", key); // §12 path-safe, §15 no content
                continue;
            }
            if (persistence.existsByObjectKey(key)) {
                continue; // dedup — already picked up
            }
            UUID jobId = persistence.createJob(concertId, null, key, "CRON"); // organizer null
            workerTrigger.ifAvailable(t -> t.trigger(jobId));
            log.info("Cron scan: created CRON job id={} concertId={} key={}", jobId, concertId, key);
        }
    }

    /** Parse {@code cron-inbox/{concertId}/{file}.csv} → concertId (UUID). Invalid → null (skip, §12). */
    private UUID parseConcertId(String key) {
        if (key == null || !key.startsWith(PREFIX)) {
            return null;
        }
        String[] seg = key.substring(PREFIX.length()).split("/");
        if (seg.length < 2 || seg[0].isBlank()) {
            return null; // need {concertId}/{file}
        }
        try {
            return UUID.fromString(seg[0]);
        } catch (IllegalArgumentException e) {
            return null; // concertId not a UUID
        }
    }
}
