package com.tickefy.csvingestion.modules.csvimport.worker;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Fires the async ingest worker after a job is created. Gated by
 * {@code app.csv.worker.auto-trigger} (matchIfMissing=true) so tests can disable auto-trigger
 * (§6.12) and keep freshly-created jobs in PENDING for assertions.
 */
@Component
@ConditionalOnProperty(name = "app.csv.worker.auto-trigger", havingValue = "true", matchIfMissing = true)
public class WorkerTrigger {

    private final CsvImportWorker worker;

    public WorkerTrigger(CsvImportWorker worker) {
        this.worker = worker;
    }

    public void trigger(UUID jobId) {
        worker.process(jobId); // cross-bean call -> @Async proxy applies (§8)
    }
}
