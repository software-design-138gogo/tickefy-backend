from datetime import datetime, timezone
from uuid import UUID, uuid4

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.ai.context_builder import SourceContextItem, context_builder
from app.ai.mock_ai_provider import mock_ai_provider
from app.core.config import get_settings
from app.core.error_codes import ErrorCode
from app.core.exceptions import ConflictException, NotFoundException
from app.db.models import (
    ConcertIntroductionJob,
    DocumentExtraction,
    JobAttempt,
    OutboxEvent,
    SourceDocument,
)
from app.schemas.generation import GeneratedIntroductionResponse


class GenerationService:
    def __init__(self) -> None:
        self.settings = get_settings()

    async def generate_introduction(
        self,
        *,
        db: Session,
        job_id: UUID,
    ) -> GeneratedIntroductionResponse:
        job = self._get_job(db=db, job_id=job_id)

        if job.status == "SUCCEEDED":
            existing_outbox = self._find_outbox_for_job(db=db, job_id=job_id)
            return self._build_response(
                job=job,
                source_document_ids=[],
                source_types=[],
                outbox_message_id=existing_outbox.message_id if existing_outbox else uuid4(),
            )

        if job.status == "FAILED" and not job.is_retryable:
            raise ConflictException(
                code=ErrorCode.AI_BIO_JOB_NOT_RETRYABLE,
                message="Job is not retryable.",
                details={"jobId": str(job_id)},
            )

        source_context_items = self._load_source_context_items(db=db, job_id=job_id)

        if not source_context_items:
            self._mark_failed_no_content(db=db, job=job)
            raise ConflictException(
                code=ErrorCode.NO_USABLE_SOURCE_CONTENT,
                message="No usable extracted content was found for this job.",
                details={"jobId": str(job_id)},
            )

        now = self._utc_now()
        job.status = "PROCESSING"
        job.processing_stage = "BUILDING_CONTEXT"
        job.updated_at = now
        db.commit()

        built_context = context_builder.build(
            concert_name=job.concert_name_snapshot,
            language=job.language,
            target_length=job.target_length,
            tone=job.tone,
            sources=source_context_items,
        )

        if not built_context.context_text.strip():
            self._mark_failed_no_content(db=db, job=job)
            raise ConflictException(
                code=ErrorCode.NO_USABLE_SOURCE_CONTENT,
                message="No usable context could be built for this job.",
                details={"jobId": str(job_id)},
            )

        attempt_no = self._next_attempt_no(db=db, job_id=job_id)
        attempt = JobAttempt(
            job_id=job_id,
            attempt_no=attempt_no,
            status="PROCESSING",
            provider_name=self.settings.ai_provider,
            provider_model=self.settings.ai_model,
            prompt_version="mock-v1",
            started_at=self._utc_now(),
            extra_metadata={
                "contextChars": built_context.total_chars,
                "sourceCount": len(built_context.source_document_ids),
            },
        )

        db.add(attempt)
        db.commit()
        db.refresh(attempt)

        job.processing_stage = "CALLING_AI"
        job.updated_at = self._utc_now()
        db.commit()

        ai_result = await mock_ai_provider.generate_concert_introduction(
            concert_name=job.concert_name_snapshot,
            context_text=built_context.context_text,
            language=job.language,
            target_length=job.target_length,
            tone=job.tone,
        )

        self._validate_output(ai_result.introduction)

        now = self._utc_now()
        message_id = uuid4()

        payload = {
            "jobId": str(job.id),
            "concertId": str(job.concert_id),
            "introduction": ai_result.introduction,
            "language": job.language,
            "sourceDocumentIds": [str(value) for value in built_context.source_document_ids],
            "sourceTypes": built_context.source_types,
            "requestedAt": self._to_iso(job.requested_at),
            "generatedAt": self._to_iso(now),
        }

        outbox_event = OutboxEvent(
            message_id=message_id,
            event_type="ConcertIntroductionGenerated",
            event_version="1.0",
            source_service="ai-bio-service",
            exchange_name="tickefy.exchange",
            routing_key="concert.introduction.generated",
            correlation_id=job.correlation_id,
            causation_id=None,
            aggregate_type="AI_BIO_JOB",
            aggregate_id=job.id,
            payload=payload,
            status="PENDING",
            retry_count=0,
            available_at=now,
            created_at=now,
            updated_at=now,
        )

        job.generated_introduction = ai_result.introduction
        job.provider_name = ai_result.provider_name
        job.provider_model = ai_result.provider_model
        job.status = "SUCCEEDED"
        job.processing_stage = "COMPLETED"
        job.completed_at = now
        job.updated_at = now
        job.error_code = None
        job.error_message = None

        attempt.status = "SUCCEEDED"
        attempt.provider_name = ai_result.provider_name
        attempt.provider_model = ai_result.provider_model
        attempt.completed_at = now
        attempt.duration_ms = self._duration_ms(attempt.started_at, now)

        db.add(outbox_event)
        db.commit()

        return self._build_response(
            job=job,
            source_document_ids=built_context.source_document_ids,
            source_types=built_context.source_types,
            outbox_message_id=message_id,
        )

    def _get_job(
        self,
        *,
        db: Session,
        job_id: UUID,
    ) -> ConcertIntroductionJob:
        statement = select(ConcertIntroductionJob).where(
            ConcertIntroductionJob.id == job_id,
        )

        job = db.execute(statement).scalar_one_or_none()

        if job is None:
            raise NotFoundException(
                code=ErrorCode.AI_BIO_JOB_NOT_FOUND,
                message="AI Bio job not found.",
                details={"jobId": str(job_id)},
            )

        return job

    def _load_source_context_items(
        self,
        *,
        db: Session,
        job_id: UUID,
    ) -> list[SourceContextItem]:
        statement = (
            select(SourceDocument, DocumentExtraction)
            .join(DocumentExtraction, DocumentExtraction.document_id == SourceDocument.id)
            .where(
                SourceDocument.job_id == job_id,
                SourceDocument.status == "EXTRACTED",
                DocumentExtraction.cleaned_text.is_not(None),
            )
            .order_by(SourceDocument.created_at.asc())
        )

        rows = db.execute(statement).all()

        return [
            SourceContextItem(
                source_document_id=source.id,
                source_type=source.source_type,
                cleaned_text=extraction.cleaned_text or "",
            )
            for source, extraction in rows
            if extraction.cleaned_text and extraction.cleaned_text.strip()
        ]

    def _next_attempt_no(
        self,
        *,
        db: Session,
        job_id: UUID,
    ) -> int:
        statement = select(func.coalesce(func.max(JobAttempt.attempt_no), 0)).where(
            JobAttempt.job_id == job_id,
        )

        current_max = db.execute(statement).scalar_one()
        return int(current_max) + 1

    def _validate_output(self, introduction: str) -> None:
        text = introduction.strip()

        if len(text) < self.settings.ai_min_output_chars:
            raise ConflictException(
                code=ErrorCode.AI_OUTPUT_INVALID,
                message="Generated introduction is too short.",
                details={"minChars": self.settings.ai_min_output_chars},
            )

        if len(text) > self.settings.ai_max_output_chars:
            raise ConflictException(
                code=ErrorCode.AI_OUTPUT_INVALID,
                message="Generated introduction is too long.",
                details={"maxChars": self.settings.ai_max_output_chars},
            )

    def _find_outbox_for_job(
        self,
        *,
        db: Session,
        job_id: UUID,
    ) -> OutboxEvent | None:
        statement = select(OutboxEvent).where(
            OutboxEvent.aggregate_type == "AI_BIO_JOB",
            OutboxEvent.aggregate_id == job_id,
            OutboxEvent.event_type == "ConcertIntroductionGenerated",
        )

        return db.execute(statement).scalar_one_or_none()

    def _mark_failed_no_content(
        self,
        *,
        db: Session,
        job: ConcertIntroductionJob,
    ) -> None:
        now = self._utc_now()
        job.status = "FAILED"
        job.processing_stage = "BUILDING_CONTEXT"
        job.error_code = ErrorCode.NO_USABLE_SOURCE_CONTENT
        job.error_message = "No usable source content was found."
        job.is_retryable = False
        job.failed_at = now
        job.updated_at = now
        db.commit()

    def _build_response(
        self,
        *,
        job: ConcertIntroductionJob,
        source_document_ids: list[UUID],
        source_types: list[str],
        outbox_message_id: UUID,
    ) -> GeneratedIntroductionResponse:
        return GeneratedIntroductionResponse(
            jobId=str(job.id),
            concertId=str(job.concert_id),
            status=job.status,
            processingStage=job.processing_stage,
            introduction=job.generated_introduction or "",
            providerName=job.provider_name or self.settings.ai_provider,
            providerModel=job.provider_model or self.settings.ai_model,
            sourceDocumentIds=[str(value) for value in source_document_ids],
            sourceTypes=source_types,
            outboxMessageId=str(outbox_message_id),
        )

    def _utc_now(self) -> datetime:
        return datetime.now(timezone.utc)

    def _to_iso(self, value: datetime) -> str:
        return value.isoformat().replace("+00:00", "Z")

    def _duration_ms(self, start: datetime, end: datetime) -> int:
        return int((end - start).total_seconds() * 1000)


generation_service = GenerationService()