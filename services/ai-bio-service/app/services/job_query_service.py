from datetime import datetime
from uuid import UUID

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.core.error_codes import ErrorCode
from app.core.exceptions import ForbiddenException, NotFoundException
from app.db.models import ConcertIntroductionJob, SourceDocument
from app.schemas.job_status import (
    AiBioJobListItem,
    AiBioJobListResponse,
    AiBioJobStatusResponse,
    JobSourceSummary,
)
from app.security.principal import CurrentUser


class JobQueryService:
    def get_job(
        self,
        *,
        db: Session,
        job_id: UUID,
        current_user: CurrentUser,
    ) -> AiBioJobStatusResponse:
        job = self._get_job(db=db, job_id=job_id)
        self._check_job_access(job=job, current_user=current_user)

        sources = self._get_sources(db=db, job_id=job_id)

        return AiBioJobStatusResponse(
            jobId=str(job.id),
            concertId=str(job.concert_id),
            concertName=job.concert_name_snapshot,
            organizerId=str(job.organizer_id_snapshot) if job.organizer_id_snapshot else None,
            status=job.status,
            processingStage=job.processing_stage,
            language=job.language,
            targetLength=job.target_length,
            tone=job.tone,
            retryCount=job.retry_count,
            maxRetries=job.max_retries,
            isRetryable=job.is_retryable,
            errorCode=job.error_code,
            errorMessage=job.error_message,
            providerName=job.provider_name,
            providerModel=job.provider_model,
            generatedIntroduction=job.generated_introduction,
            sourceCount=len(sources),
            sources=[
                JobSourceSummary(
                    sourceDocumentId=str(source.id),
                    sourceType=source.source_type,
                    status=source.status,
                    originalFileName=source.original_file_name,
                    fileSizeBytes=source.file_size_bytes,
                    warningCount=source.warning_count,
                    extractionErrorCode=source.extraction_error_code,
                )
                for source in sources
            ],
            requestedAt=self._to_iso(job.requested_at),
            startedAt=self._to_iso(job.started_at),
            completedAt=self._to_iso(job.completed_at),
            failedAt=self._to_iso(job.failed_at),
            createdAt=self._to_iso(job.created_at),
            updatedAt=self._to_iso(job.updated_at),
        )

    def list_jobs_by_concert(
        self,
        *,
        db: Session,
        concert_id: UUID,
        current_user: CurrentUser,
        limit: int,
        offset: int,
    ) -> AiBioJobListResponse:
        jobs = self._list_jobs(
            db=db,
            concert_id=concert_id,
            limit=limit,
            offset=offset,
        )

        if jobs:
            self._check_job_access(job=jobs[0], current_user=current_user)

        total = self._count_jobs(db=db, concert_id=concert_id)

        return AiBioJobListResponse(
            concertId=str(concert_id),
            total=total,
            items=[
                AiBioJobListItem(
                    jobId=str(job.id),
                    concertId=str(job.concert_id),
                    status=job.status,
                    processingStage=job.processing_stage,
                    language=job.language,
                    targetLength=job.target_length,
                    tone=job.tone,
                    retryCount=job.retry_count,
                    errorCode=job.error_code,
                    providerName=job.provider_name,
                    providerModel=job.provider_model,
                    requestedAt=self._to_iso(job.requested_at),
                    completedAt=self._to_iso(job.completed_at),
                    failedAt=self._to_iso(job.failed_at),
                    createdAt=self._to_iso(job.created_at),
                )
                for job in jobs
            ],
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

    def _get_sources(
        self,
        *,
        db: Session,
        job_id: UUID,
    ) -> list[SourceDocument]:
        statement = (
            select(SourceDocument)
            .where(SourceDocument.job_id == job_id)
            .order_by(SourceDocument.created_at.asc())
        )

        return list(db.execute(statement).scalars().all())

    def _list_jobs(
        self,
        *,
        db: Session,
        concert_id: UUID,
        limit: int,
        offset: int,
    ) -> list[ConcertIntroductionJob]:
        statement = (
            select(ConcertIntroductionJob)
            .where(ConcertIntroductionJob.concert_id == concert_id)
            .order_by(ConcertIntroductionJob.created_at.desc())
            .limit(limit)
            .offset(offset)
        )

        return list(db.execute(statement).scalars().all())

    def _count_jobs(
        self,
        *,
        db: Session,
        concert_id: UUID,
    ) -> int:
        statement = select(func.count()).select_from(ConcertIntroductionJob).where(
            ConcertIntroductionJob.concert_id == concert_id,
        )

        return int(db.execute(statement).scalar_one())

    def _check_job_access(
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
            message="You do not have permission to access this AI Bio job.",
            details={"jobId": str(job.id)},
        )

    def _to_iso(self, value: datetime | None) -> str | None:
        if value is None:
            return None

        return value.isoformat().replace("+00:00", "Z")


job_query_service = JobQueryService()
