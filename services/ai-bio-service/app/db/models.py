from datetime import datetime
from typing import Any
from uuid import UUID

from sqlalchemy import (
    BigInteger,
    Boolean,
    CheckConstraint,
    DateTime,
    ForeignKey,
    Index,
    Integer,
    String,
    Text,
    UniqueConstraint,
    text,
)
from sqlalchemy.dialects.postgresql import JSONB, UUID as PG_UUID
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship

from app.core.config import get_settings

settings = get_settings()
SCHEMA = settings.db_schema


class Base(DeclarativeBase):
    pass


class ConcertIntroductionJob(Base):
    __tablename__ = "concert_introduction_jobs"
    __table_args__ = (
        CheckConstraint(
            "status IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED')",
            name="ck_ai_bio_jobs_status",
        ),
        CheckConstraint(
            "processing_stage IN ("
            "'RECEIVED', 'STORING_SOURCES', 'EXTRACTING_TEXT', 'CLEANING_TEXT', "
            "'BUILDING_CONTEXT', 'CALLING_AI', 'VALIDATING_OUTPUT', "
            "'PUBLISHING_RESULT', 'COMPLETED'"
            ")",
            name="ck_ai_bio_jobs_processing_stage",
        ),
        CheckConstraint("retry_count >= 0", name="ck_ai_bio_jobs_retry_count_non_negative"),
        CheckConstraint("max_retries >= 0", name="ck_ai_bio_jobs_max_retries_non_negative"),
        Index("idx_ai_bio_jobs_concert_created_at", "concert_id", "created_at"),
        Index("idx_ai_bio_jobs_status_created_at", "status", "created_at"),
        Index(
            "uq_ai_bio_active_job_per_concert",
            "concert_id",
            unique=True,
            postgresql_where=text("status IN ('PENDING', 'PROCESSING')"),
        ),
        {"schema": SCHEMA},
    )

    id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        primary_key=True,
        server_default=text("gen_random_uuid()"),
    )

    concert_id: Mapped[UUID] = mapped_column(PG_UUID(as_uuid=True), nullable=False)
    concert_name_snapshot: Mapped[str] = mapped_column(String(255), nullable=False)
    organizer_id_snapshot: Mapped[UUID] = mapped_column(PG_UUID(as_uuid=True), nullable=False)

    created_by: Mapped[UUID] = mapped_column(PG_UUID(as_uuid=True), nullable=False)
    created_by_role: Mapped[str] = mapped_column(String(50), nullable=False)

    correlation_id: Mapped[str] = mapped_column(String(120), nullable=False)
    idempotency_key: Mapped[str] = mapped_column(String(200), nullable=False)

    status: Mapped[str] = mapped_column(String(30), nullable=False, server_default="PENDING")
    processing_stage: Mapped[str] = mapped_column(
        String(50),
        nullable=False,
        server_default="RECEIVED",
    )

    language: Mapped[str] = mapped_column(String(10), nullable=False, server_default="vi")
    target_length: Mapped[str] = mapped_column(String(30), nullable=False, server_default="SHORT")
    tone: Mapped[str | None] = mapped_column(String(30), nullable=True)

    retry_count: Mapped[int] = mapped_column(Integer, nullable=False, server_default="0")
    max_retries: Mapped[int] = mapped_column(Integer, nullable=False, server_default="3")

    warning_count: Mapped[int] = mapped_column(Integer, nullable=False, server_default="0")
    error_code: Mapped[str | None] = mapped_column(String(100), nullable=True)
    error_message: Mapped[str | None] = mapped_column(Text, nullable=True)
    is_retryable: Mapped[bool] = mapped_column(Boolean, nullable=False, server_default="true")

    generated_introduction: Mapped[str | None] = mapped_column(Text, nullable=True)
    provider_name: Mapped[str | None] = mapped_column(String(80), nullable=True)
    provider_model: Mapped[str | None] = mapped_column(String(120), nullable=True)

    requested_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=text("NOW()"),
    )
    started_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    completed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    failed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)

    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=text("NOW()"),
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=text("NOW()"),
    )

    documents = relationship("SourceDocument", back_populates="job", cascade="all, delete-orphan")
    attempts = relationship("JobAttempt", back_populates="job", cascade="all, delete-orphan")


class SourceDocument(Base):
    __tablename__ = "source_documents"
    __table_args__ = (
        CheckConstraint(
            "source_type IN ('PDF', 'MARKDOWN', 'TEXT', 'DOCX', 'PPTX', 'IMAGE', 'URL')",
            name="ck_ai_bio_source_documents_source_type",
        ),
        CheckConstraint(
            "status IN ('STORED', 'EXTRACTION_PENDING', 'EXTRACTED', 'FAILED', 'SKIPPED')",
            name="ck_ai_bio_source_documents_status",
        ),
        CheckConstraint(
            "object_key IS NOT NULL OR source_url IS NOT NULL",
            name="ck_ai_bio_source_documents_has_source_reference",
        ),
        Index("idx_ai_bio_source_documents_job_id", "job_id"),
        Index("idx_ai_bio_source_documents_checksum", "checksum_sha256"),
        {"schema": SCHEMA},
    )

    id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        primary_key=True,
        server_default=text("gen_random_uuid()"),
    )
    job_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey(f"{SCHEMA}.concert_introduction_jobs.id", ondelete="CASCADE"),
        nullable=False,
    )

    source_type: Mapped[str] = mapped_column(String(30), nullable=False)
    status: Mapped[str] = mapped_column(String(30), nullable=False, server_default="STORED")

    original_file_name: Mapped[str | None] = mapped_column(String(255), nullable=True)
    content_type: Mapped[str | None] = mapped_column(String(150), nullable=True)
    file_size_bytes: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    checksum_sha256: Mapped[str | None] = mapped_column(String(64), nullable=True)

    object_key: Mapped[str | None] = mapped_column(String(700), nullable=True)
    source_url: Mapped[str | None] = mapped_column(Text, nullable=True)
    url_host: Mapped[str | None] = mapped_column(String(255), nullable=True)

    extraction_error_code: Mapped[str | None] = mapped_column(String(100), nullable=True)
    extraction_error_message: Mapped[str | None] = mapped_column(Text, nullable=True)
    warning_count: Mapped[int] = mapped_column(Integer, nullable=False, server_default="0")

    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=text("NOW()"),
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=text("NOW()"),
    )

    job = relationship("ConcertIntroductionJob", back_populates="documents")
    extraction = relationship(
        "DocumentExtraction",
        back_populates="document",
        cascade="all, delete-orphan",
        uselist=False,
    )


class DocumentExtraction(Base):
    __tablename__ = "document_extractions"
    __table_args__ = (
        UniqueConstraint("document_id", name="uq_ai_bio_document_extractions_document_id"),
        Index("idx_ai_bio_document_extractions_document_id", "document_id"),
        {"schema": SCHEMA},
    )

    id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        primary_key=True,
        server_default=text("gen_random_uuid()"),
    )
    document_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey(f"{SCHEMA}.source_documents.id", ondelete="CASCADE"),
        nullable=False,
    )

    extracted_text: Mapped[str | None] = mapped_column(Text, nullable=True)
    cleaned_text: Mapped[str | None] = mapped_column(Text, nullable=True)

    extracted_char_count: Mapped[int] = mapped_column(Integer, nullable=False, server_default="0")
    cleaned_char_count: Mapped[int] = mapped_column(Integer, nullable=False, server_default="0")

    parser_name: Mapped[str | None] = mapped_column(String(100), nullable=True)
    parser_version: Mapped[str | None] = mapped_column(String(50), nullable=True)

    warnings: Mapped[list[Any]] = mapped_column(
        JSONB,
        nullable=False,
        server_default=text("'[]'::jsonb"),
    )
    extra_metadata: Mapped[dict[str, Any]] = mapped_column(
        "metadata",
        JSONB,
        nullable=False,
        server_default=text("'{}'::jsonb"),
    )

    extraction_started_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    extraction_completed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)

    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=text("NOW()"),
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=text("NOW()"),
    )

    document = relationship("SourceDocument", back_populates="extraction")


class JobAttempt(Base):
    __tablename__ = "job_attempts"
    __table_args__ = (
        CheckConstraint(
            "status IN ('PROCESSING', 'SUCCEEDED', 'FAILED')",
            name="ck_ai_bio_job_attempts_status",
        ),
        UniqueConstraint("job_id", "attempt_no", name="uq_ai_bio_job_attempts_job_attempt_no"),
        Index("idx_ai_bio_job_attempts_job_id", "job_id"),
        {"schema": SCHEMA},
    )

    id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        primary_key=True,
        server_default=text("gen_random_uuid()"),
    )
    job_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey(f"{SCHEMA}.concert_introduction_jobs.id", ondelete="CASCADE"),
        nullable=False,
    )

    attempt_no: Mapped[int] = mapped_column(Integer, nullable=False)
    status: Mapped[str] = mapped_column(String(30), nullable=False, server_default="PROCESSING")

    provider_name: Mapped[str | None] = mapped_column(String(80), nullable=True)
    provider_model: Mapped[str | None] = mapped_column(String(120), nullable=True)
    prompt_version: Mapped[str | None] = mapped_column(String(50), nullable=True)

    duration_ms: Mapped[int | None] = mapped_column(Integer, nullable=True)
    error_code: Mapped[str | None] = mapped_column(String(100), nullable=True)
    error_message: Mapped[str | None] = mapped_column(Text, nullable=True)
    is_retryable: Mapped[bool] = mapped_column(Boolean, nullable=False, server_default="true")

    extra_metadata: Mapped[dict[str, Any]] = mapped_column(
        "metadata",
        JSONB,
        nullable=False,
        server_default=text("'{}'::jsonb"),
    )

    started_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=text("NOW()"),
    )
    completed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)

    job = relationship("ConcertIntroductionJob", back_populates="attempts")


class OutboxEvent(Base):
    __tablename__ = "outbox_events"
    __table_args__ = (
        CheckConstraint(
            "status IN ('PENDING', 'PUBLISHED', 'FAILED')",
            name="ck_ai_bio_outbox_events_status",
        ),
        UniqueConstraint("message_id", name="uq_ai_bio_outbox_events_message_id"),
        Index("idx_ai_bio_outbox_events_status_available_at", "status", "available_at"),
        Index("idx_ai_bio_outbox_events_aggregate", "aggregate_type", "aggregate_id"),
        {"schema": SCHEMA},
    )

    id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        primary_key=True,
        server_default=text("gen_random_uuid()"),
    )

    message_id: Mapped[UUID] = mapped_column(PG_UUID(as_uuid=True), nullable=False)
    event_type: Mapped[str] = mapped_column(String(120), nullable=False)
    event_version: Mapped[str] = mapped_column(String(20), nullable=False, server_default="1.0")
    source_service: Mapped[str] = mapped_column(String(120), nullable=False, server_default="ai-bio-service")

    exchange_name: Mapped[str] = mapped_column(String(120), nullable=False, server_default="tickefy.exchange")
    routing_key: Mapped[str] = mapped_column(String(160), nullable=False)

    correlation_id: Mapped[str] = mapped_column(String(120), nullable=False)
    causation_id: Mapped[UUID | None] = mapped_column(PG_UUID(as_uuid=True), nullable=True)

    aggregate_type: Mapped[str] = mapped_column(String(80), nullable=False)
    aggregate_id: Mapped[UUID] = mapped_column(PG_UUID(as_uuid=True), nullable=False)

    payload: Mapped[dict[str, Any]] = mapped_column(JSONB, nullable=False)

    status: Mapped[str] = mapped_column(String(30), nullable=False, server_default="PENDING")
    retry_count: Mapped[int] = mapped_column(Integer, nullable=False, server_default="0")

    available_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=text("NOW()"),
    )
    published_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    last_error: Mapped[str | None] = mapped_column(Text, nullable=True)

    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=text("NOW()"),
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=text("NOW()"),
    )


class IdempotencyRecord(Base):
    __tablename__ = "idempotency_records"
    __table_args__ = (
        CheckConstraint(
            "action IN ('CREATE_JOB', 'RETRY_JOB')",
            name="ck_ai_bio_idempotency_records_action",
        ),
        UniqueConstraint(
            "created_by",
            "idempotency_key",
            "action",
            name="uq_ai_bio_idempotency_records_scope",
        ),
        Index("idx_ai_bio_idempotency_records_job_id", "job_id"),
        Index("idx_ai_bio_idempotency_records_expires_at", "expires_at"),
        {"schema": SCHEMA},
    )

    id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        primary_key=True,
        server_default=text("gen_random_uuid()"),
    )

    created_by: Mapped[UUID] = mapped_column(PG_UUID(as_uuid=True), nullable=False)
    idempotency_key: Mapped[str] = mapped_column(String(200), nullable=False)
    action: Mapped[str] = mapped_column(String(40), nullable=False)

    request_hash: Mapped[str] = mapped_column(String(64), nullable=False)
    response_status: Mapped[int | None] = mapped_column(Integer, nullable=True)
    response_body: Mapped[dict[str, Any] | None] = mapped_column(JSONB, nullable=True)

    job_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey(f"{SCHEMA}.concert_introduction_jobs.id", ondelete="SET NULL"),
        nullable=True,
    )

    replay_count: Mapped[int] = mapped_column(Integer, nullable=False, server_default="0")
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)

    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=text("NOW()"),
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=text("NOW()"),
    )