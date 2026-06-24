ALTER TABLE concerts RENAME COLUMN ai_introduction TO concert_introduction;
ALTER TABLE concerts RENAME COLUMN ai_introduction_updated_at TO concert_introduction_updated_at;

ALTER TABLE concerts
    ADD COLUMN concert_introduction_source_job_id UUID,
    ADD COLUMN concert_introduction_language VARCHAR(10),
    ADD COLUMN manual_introduction_updated_at TIMESTAMPTZ;

CREATE INDEX idx_concerts_introduction_source_job
    ON concerts(concert_introduction_source_job_id)
    WHERE concert_introduction_source_job_id IS NOT NULL;
