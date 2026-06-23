from uuid import UUID

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core.error_codes import ErrorCode
from app.core.exceptions import ConflictException, NotFoundException
from app.db.models import ConcertIntroductionJob
from app.schemas.pipeline import PipelineRunResponse
from app.services.extraction_worker_service import extraction_worker_service
from app.services.generation_service import generation_service
from app.services.outbox_publisher_service import outbox_publisher_service


class PipelineWorkerService:
    async def run_job_pipeline(
        self,
        *,
        db: Session,
        job_id: UUID,
    ) -> PipelineRunResponse:
        job = self._get_job(db=db, job_id=job_id)
        
        if job.status == "FAILED":
          raise ConflictException(
              code=ErrorCode.CONFLICT,
              message="AI Bio job already failed. Create a new job or use a retry flow.",
              details={
                  "jobId": str(job.id),
                  "status": job.status,
                  "processingStage": job.processing_stage,
                  "errorCode": job.error_code,
                  "errorMessage": job.error_message,
                  "isRetryable": job.is_retryable,
              },
          )

        extraction = None
        generation = None
        outbox = None

        if job.status in {"PENDING", "PROCESSING"} and job.processing_stage in {
            "RECEIVED",
            "STORING_SOURCES",
            "EXTRACTING_TEXT",
        }:
            extraction = await extraction_worker_service.extract_job_sources(
                db=db,
                job_id=job_id,
            )
            db.refresh(job)

        if job.status != "FAILED":
            generation = await generation_service.generate_introduction(
                db=db,
                job_id=job_id,
            )
            db.refresh(job)

            outbox = await outbox_publisher_service.publish_pending_events(
                db=db,
                limit=10,
                aggregate_id=job_id,
            )
            db.refresh(job)

        return PipelineRunResponse(
            jobId=str(job.id),
            concertId=str(job.concert_id),
            finalStatus=job.status,
            finalProcessingStage=job.processing_stage,
            extraction=extraction,
            generation=generation,
            outbox=outbox,
        )

    async def run_next_pending_job(
        self,
        *,
        db: Session,
    ) -> PipelineRunResponse:
        job = self._find_next_pending_job(db=db)

        if job is None:
            raise NotFoundException(
                code=ErrorCode.RESOURCE_NOT_FOUND,
                message="No pending AI Bio job was found.",
            )

        return await self.run_job_pipeline(
            db=db,
            job_id=job.id,
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

    def _find_next_pending_job(
        self,
        *,
        db: Session,
    ) -> ConcertIntroductionJob | None:
        statement = (
            select(ConcertIntroductionJob)
            .where(ConcertIntroductionJob.status == "PENDING")
            .order_by(ConcertIntroductionJob.created_at.asc())
            .limit(1)
        )

        return db.execute(statement).scalar_one_or_none()


pipeline_worker_service = PipelineWorkerService()