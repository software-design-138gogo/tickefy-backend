"""create ai bio schema

Revision ID: 0001_create_ai_bio_schema
Revises:
Create Date: 2026-06-19
"""

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision = "0001_create_ai_bio_schema"
down_revision = None
branch_labels = None
depends_on = None

SCHEMA = "ai_bio_schema"


def upgrade() -> None:
    op.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto")
    op.execute(f"CREATE SCHEMA IF NOT EXISTS {SCHEMA}")

    op.create_table(
        "concert_introduction_jobs",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True, server_default=sa.text("gen_random_uuid()")),
        sa.Column("concert_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("concert_name_snapshot", sa.String(length=255), nullable=False),
        sa.Column("organizer_id_snapshot", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("created_by", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("created_by_role", sa.String(length=50), nullable=False),
        sa.Column("correlation_id", sa.String(length=120), nullable=False),
        sa.Column("idempotency_key", sa.String(length=200), nullable=False),
        sa.Column("status", sa.String(length=30), nullable=False, server_default="PENDING"),
        sa.Column("processing_stage", sa.String(length=50), nullable=False, server_default="RECEIVED"),
        sa.Column("language", sa.String(length=10), nullable=False, server_default="vi"),
        sa.Column("target_length", sa.String(length=30), nullable=False, server_default="SHORT"),
        sa.Column("tone", sa.String(length=30), nullable=True),
        sa.Column("retry_count", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("max_retries", sa.Integer(), nullable=False, server_default="3"),
        sa.Column("warning_count", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("error_code", sa.String(length=100), nullable=True),
        sa.Column("error_message", sa.Text(), nullable=True),
        sa.Column("is_retryable", sa.Boolean(), nullable=False, server_default=sa.text("true")),
        sa.Column("generated_introduction", sa.Text(), nullable=True),
        sa.Column("provider_name", sa.String(length=80), nullable=True),
        sa.Column("provider_model", sa.String(length=120), nullable=True),
        sa.Column("requested_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.text("NOW()")),
        sa.Column("started_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("completed_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("failed_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.text("NOW()")),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.text("NOW()")),
        sa.CheckConstraint(
            "status IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED')",
            name="ck_ai_bio_jobs_status",
        ),
        sa.CheckConstraint(
            "processing_stage IN ("
            "'RECEIVED', 'STORING_SOURCES', 'EXTRACTING_TEXT', 'CLEANING_TEXT', "
            "'BUILDING_CONTEXT', 'CALLING_AI', 'VALIDATING_OUTPUT', "
            "'PUBLISHING_RESULT', 'COMPLETED'"
            ")",
            name="ck_ai_bio_jobs_processing_stage",
        ),
        sa.CheckConstraint("retry_count >= 0", name="ck_ai_bio_jobs_retry_count_non_negative"),
        sa.CheckConstraint("max_retries >= 0", name="ck_ai_bio_jobs_max_retries_non_negative"),
        schema=SCHEMA,
    )

    op.create_index(
        "idx_ai_bio_jobs_concert_created_at",
        "concert_introduction_jobs",
        ["concert_id", "created_at"],
        schema=SCHEMA,
    )
    op.create_index(
        "idx_ai_bio_jobs_status_created_at",
        "concert_introduction_jobs",
        ["status", "created_at"],
        schema=SCHEMA,
    )
    op.create_index(
        "uq_ai_bio_active_job_per_concert",
        "concert_introduction_jobs",
        ["concert_id"],
        unique=True,
        schema=SCHEMA,
        postgresql_where=sa.text("status IN ('PENDING', 'PROCESSING')"),
    )

    op.create_table(
        "source_documents",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True, server_default=sa.text("gen_random_uuid()")),
        sa.Column("job_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("source_type", sa.String(length=30), nullable=False),
        sa.Column("status", sa.String(length=30), nullable=False, server_default="STORED"),
        sa.Column("original_file_name", sa.String(length=255), nullable=True),
        sa.Column("content_type", sa.String(length=150), nullable=True),
        sa.Column("file_size_bytes", sa.BigInteger(), nullable=True),
        sa.Column("checksum_sha256", sa.String(length=64), nullable=True),
        sa.Column("object_key", sa.String(length=700), nullable=True),
        sa.Column("source_url", sa.Text(), nullable=True),
        sa.Column("url_host", sa.String(length=255), nullable=True),
        sa.Column("extraction_error_code", sa.String(length=100), nullable=True),
        sa.Column("extraction_error_message", sa.Text(), nullable=True),
        sa.Column("warning_count", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.text("NOW()")),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.text("NOW()")),
        sa.ForeignKeyConstraint(
            ["job_id"],
            [f"{SCHEMA}.concert_introduction_jobs.id"],
            ondelete="CASCADE",
            name="fk_ai_bio_source_documents_job_id",
        ),
        sa.CheckConstraint(
            "source_type IN ('PDF', 'MARKDOWN', 'TEXT', 'DOCX', 'PPTX', 'IMAGE', 'URL')",
            name="ck_ai_bio_source_documents_source_type",
        ),
        sa.CheckConstraint(
            "status IN ('STORED', 'EXTRACTION_PENDING', 'EXTRACTED', 'FAILED', 'SKIPPED')",
            name="ck_ai_bio_source_documents_status",
        ),
        sa.CheckConstraint(
            "object_key IS NOT NULL OR source_url IS NOT NULL",
            name="ck_ai_bio_source_documents_has_source_reference",
        ),
        schema=SCHEMA,
    )

    op.create_index("idx_ai_bio_source_documents_job_id", "source_documents", ["job_id"], schema=SCHEMA)
    op.create_index("idx_ai_bio_source_documents_checksum", "source_documents", ["checksum_sha256"], schema=SCHEMA)

    op.create_table(
        "document_extractions",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True, server_default=sa.text("gen_random_uuid()")),
        sa.Column("document_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("extracted_text", sa.Text(), nullable=True),
        sa.Column("cleaned_text", sa.Text(), nullable=True),
        sa.Column("extracted_char_count", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("cleaned_char_count", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("parser_name", sa.String(length=100), nullable=True),
        sa.Column("parser_version", sa.String(length=50), nullable=True),
        sa.Column("warnings", postgresql.JSONB(), nullable=False, server_default=sa.text("'[]'::jsonb")),
        sa.Column("metadata", postgresql.JSONB(), nullable=False, server_default=sa.text("'{}'::jsonb")),
        sa.Column("extraction_started_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("extraction_completed_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.text("NOW()")),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.text("NOW()")),
        sa.ForeignKeyConstraint(
            ["document_id"],
            [f"{SCHEMA}.source_documents.id"],
            ondelete="CASCADE",
            name="fk_ai_bio_document_extractions_document_id",
        ),
        sa.UniqueConstraint("document_id", name="uq_ai_bio_document_extractions_document_id"),
        schema=SCHEMA,
    )

    op.create_index(
        "idx_ai_bio_document_extractions_document_id",
        "document_extractions",
        ["document_id"],
        schema=SCHEMA,
    )

    op.create_table(
        "job_attempts",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True, server_default=sa.text("gen_random_uuid()")),
        sa.Column("job_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("attempt_no", sa.Integer(), nullable=False),
        sa.Column("status", sa.String(length=30), nullable=False, server_default="PROCESSING"),
        sa.Column("provider_name", sa.String(length=80), nullable=True),
        sa.Column("provider_model", sa.String(length=120), nullable=True),
        sa.Column("prompt_version", sa.String(length=50), nullable=True),
        sa.Column("duration_ms", sa.Integer(), nullable=True),
        sa.Column("error_code", sa.String(length=100), nullable=True),
        sa.Column("error_message", sa.Text(), nullable=True),
        sa.Column("is_retryable", sa.Boolean(), nullable=False, server_default=sa.text("true")),
        sa.Column("metadata", postgresql.JSONB(), nullable=False, server_default=sa.text("'{}'::jsonb")),
        sa.Column("started_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.text("NOW()")),
        sa.Column("completed_at", sa.DateTime(timezone=True), nullable=True),
        sa.ForeignKeyConstraint(
            ["job_id"],
            [f"{SCHEMA}.concert_introduction_jobs.id"],
            ondelete="CASCADE",
            name="fk_ai_bio_job_attempts_job_id",
        ),
        sa.CheckConstraint(
            "status IN ('PROCESSING', 'SUCCEEDED', 'FAILED')",
            name="ck_ai_bio_job_attempts_status",
        ),
        sa.UniqueConstraint("job_id", "attempt_no", name="uq_ai_bio_job_attempts_job_attempt_no"),
        schema=SCHEMA,
    )

    op.create_index("idx_ai_bio_job_attempts_job_id", "job_attempts", ["job_id"], schema=SCHEMA)

    op.create_table(
        "outbox_events",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True, server_default=sa.text("gen_random_uuid()")),
        sa.Column("message_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("event_type", sa.String(length=120), nullable=False),
        sa.Column("event_version", sa.String(length=20), nullable=False, server_default="1.0"),
        sa.Column("source_service", sa.String(length=120), nullable=False, server_default="ai-bio-service"),
        sa.Column("exchange_name", sa.String(length=120), nullable=False, server_default="tickefy.exchange"),
        sa.Column("routing_key", sa.String(length=160), nullable=False),
        sa.Column("correlation_id", sa.String(length=120), nullable=False),
        sa.Column("causation_id", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column("aggregate_type", sa.String(length=80), nullable=False),
        sa.Column("aggregate_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("payload", postgresql.JSONB(), nullable=False),
        sa.Column("status", sa.String(length=30), nullable=False, server_default="PENDING"),
        sa.Column("retry_count", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("available_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.text("NOW()")),
        sa.Column("published_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("last_error", sa.Text(), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.text("NOW()")),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.text("NOW()")),
        sa.CheckConstraint(
            "status IN ('PENDING', 'PUBLISHED', 'FAILED')",
            name="ck_ai_bio_outbox_events_status",
        ),
        sa.UniqueConstraint("message_id", name="uq_ai_bio_outbox_events_message_id"),
        schema=SCHEMA,
    )

    op.create_index(
        "idx_ai_bio_outbox_events_status_available_at",
        "outbox_events",
        ["status", "available_at"],
        schema=SCHEMA,
    )
    op.create_index(
        "idx_ai_bio_outbox_events_aggregate",
        "outbox_events",
        ["aggregate_type", "aggregate_id"],
        schema=SCHEMA,
    )

    op.create_table(
        "idempotency_records",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True, server_default=sa.text("gen_random_uuid()")),
        sa.Column("created_by", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("idempotency_key", sa.String(length=200), nullable=False),
        sa.Column("action", sa.String(length=40), nullable=False),
        sa.Column("request_hash", sa.String(length=64), nullable=False),
        sa.Column("response_status", sa.Integer(), nullable=True),
        sa.Column("response_body", postgresql.JSONB(), nullable=True),
        sa.Column("job_id", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column("replay_count", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.text("NOW()")),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.text("NOW()")),
        sa.ForeignKeyConstraint(
            ["job_id"],
            [f"{SCHEMA}.concert_introduction_jobs.id"],
            ondelete="SET NULL",
            name="fk_ai_bio_idempotency_records_job_id",
        ),
        sa.CheckConstraint(
            "action IN ('CREATE_JOB', 'RETRY_JOB')",
            name="ck_ai_bio_idempotency_records_action",
        ),
        sa.UniqueConstraint(
            "created_by",
            "idempotency_key",
            "action",
            name="uq_ai_bio_idempotency_records_scope",
        ),
        schema=SCHEMA,
    )

    op.create_index("idx_ai_bio_idempotency_records_job_id", "idempotency_records", ["job_id"], schema=SCHEMA)
    op.create_index("idx_ai_bio_idempotency_records_expires_at", "idempotency_records", ["expires_at"], schema=SCHEMA)


def downgrade() -> None:
    op.drop_table("idempotency_records", schema=SCHEMA)
    op.drop_table("outbox_events", schema=SCHEMA)
    op.drop_table("job_attempts", schema=SCHEMA)
    op.drop_table("document_extractions", schema=SCHEMA)
    op.drop_table("source_documents", schema=SCHEMA)
    op.drop_table("concert_introduction_jobs", schema=SCHEMA)
