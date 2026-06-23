from datetime import datetime, timezone
from uuid import UUID

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core.error_codes import ErrorCode
from app.core.exceptions import ConflictException, ForbiddenException, NotFoundException
from app.db.models import ConcertIntroductionJob, DocumentExtraction, OutboxEvent, SourceDocument
from app.schemas.retry import RetryJobResponse
from app.security.principal import CurrentUser


class JobRetryService:
    def retry_job(
        self,
        *,
        db: Session,
        job_id: UUID,
        current_user: CurrentUser,
    ) -> RetryJobResponse:
        job = self._get_job(db=db, job_id=job_id)

        self._check_permission(
            job=job,
            current_user=current_user,
        )

        if job.status != "FAILED":
            raise ConflictException(
                code=ErrorCode.AI_BIO_JOB_NOT_RETRYABLE,
                message="Only failed jobs can be retried.",
                details={
                    "jobId": str(job.id),
                    "status": job.status,
                },
            )

        if not job.is_retryable:
            raise ConflictException(
                code=ErrorCode.AI_BIO_JOB_NOT_RETRYABLE,
                message="This job is not retryable.",
                details={"jobId": str(job.id)},
            )

        if job.retry_count >= job.max_retries:
            raise ConflictException(
                code=ErrorCode.AI_BIO_JOB_NOT_RETRYABLE,
                message="Maximum retry count has been reached.",
                details={
                    "jobId": str(job.id),
                    "retryCount": job.retry_count,
                    "maxRetries": job.max_retries,
                },
            )

        self._reset_job_for_retry(
            db=db,
            job=job,
        )

        db.commit()
        db.refresh(job)

        return RetryJobResponse(
            jobId=str(job.id),
            concertId=str(job.concert_id),
            status=job.status,
            processingStage=job.processing_stage,
            retryCount=job.retry_count,
            maxRetries=job.max_retries,
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

    def _check_permission(
        self,
        *,
        job: ConcertIntroductionJob,
        current_user: CurrentUser,
    ) -> None:
        if current_user.has_role("ADMIN"):
            return

        if job.created_by == current_user.user_id:
            return

        if job.organizer_id_snapshot == current_user.user_id:
            return

        raise ForbiddenException(
            code=ErrorCode.CONCERT_ACCESS_DENIED,
            message="You do not have permission to retry this job.",
            details={"jobId": str(job.id)},
        )

    def _reset_job_for_retry(
        self,
        *,
        db: Session,
        job: ConcertIntroductionJob,
    ) -> None:
        now = self._utc_now()

        job.retry_count += 1
        job.status = "PENDING"
        job.processing_stage = "RECEIVED"
        job.error_code = None
        job.error_message = None
        job.failed_at = None
        job.completed_at = None
        job.started_at = None
        job.generated_introduction = None
        job.provider_name = None
        job.provider_model = None
        job.updated_at = now

        self._reset_source_documents(
            db=db,
            job_id=job.id,
            now=now,
        )

        self._delete_existing_outbox_for_job(
            db=db,
            job_id=job.id,
        )

    def _reset_source_documents(
        self,
        *,
        db: Session,
        job_id: UUID,
        now: datetime,
    ) -> None:
        source_statement = select(SourceDocument).where(
            SourceDocument.job_id == job_id,
        )

        sources = list(db.execute(source_statement).scalars().all())

        for source in sources:
            source.status = "STORED"
            source.extraction_error_code = None
            source.extraction_error_message = None
            source.warning_count = 0
            source.updated_at = now

            extraction_statement = select(DocumentExtraction).where(
                DocumentExtraction.document_id == source.id,
            )

            extraction = db.execute(extraction_statement).scalar_one_or_none()

            if extraction is not None:
                db.delete(extraction)

    def _delete_existing_outbox_for_job(
        self,
        *,
        db: Session,
        job_id: UUID,
    ) -> None:
        statement = select(OutboxEvent).where(
            OutboxEvent.aggregate_type == "AI_BIO_JOB",
            OutboxEvent.aggregate_id == job_id,
            OutboxEvent.event_type == "ConcertIntroductionGenerated",
            OutboxEvent.status != "PUBLISHED",
        )

        events = list(db.execute(statement).scalars().all())

        for event in events:
            db.delete(event)

    def _utc_now(self) -> datetime:
        return datetime.now(timezone.utc)


job_retry_service = JobRetryService()