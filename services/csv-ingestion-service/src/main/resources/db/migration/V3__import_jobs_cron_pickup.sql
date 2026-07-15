-- csv_ingestion_schema: V3 - allow CRON-sourced import jobs (no uploader/JWT -> organizer_id null)

ALTER TABLE import_jobs ALTER COLUMN organizer_id DROP NOT NULL;

-- UPLOAD still requires organizer_id; CRON (system-initiated batch pickup) may omit it.
ALTER TABLE import_jobs ADD CONSTRAINT chk_import_jobs_organizer
    CHECK ((source = 'UPLOAD' AND organizer_id IS NOT NULL) OR source = 'CRON');
