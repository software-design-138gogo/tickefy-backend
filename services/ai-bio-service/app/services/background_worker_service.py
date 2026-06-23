import asyncio
import logging
from contextlib import suppress
from uuid import UUID

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core.config import get_settings
from app.db.models import ConcertIntroductionJob
from app.db.session import SessionLocal
from app.services.pipeline_worker_service import pipeline_worker_service

logger = logging.getLogger(__name__)


class BackgroundWorkerService:
    def __init__(self) -> None:
        self.settings = get_settings()
        self._task: asyncio.Task | None = None
        self._stopping = asyncio.Event()

    def start(self) -> None:
        if not self.settings.worker_enabled:
            logger.info("AI Bio background worker is disabled")
            return

        if self._task is not None and not self._task.done():
            logger.info("AI Bio background worker is already running")
            return

        self._stopping.clear()
        self._task = asyncio.create_task(self._run_loop())
        logger.info("AI Bio background worker started")

    async def stop(self) -> None:
        self._stopping.set()

        if self._task is None:
            return

        self._task.cancel()

        with suppress(asyncio.CancelledError):
            await self._task

        logger.info("AI Bio background worker stopped")

    async def _run_loop(self) -> None:
        empty_polls = 0

        while not self._stopping.is_set():
            try:
                processed_count = await self._process_once()

                if processed_count == 0:
                    empty_polls += 1

                    if empty_polls >= self.settings.worker_max_empty_polls_before_log:
                        logger.info("AI Bio background worker found no pending jobs")
                        empty_polls = 0
                else:
                    empty_polls = 0

            except Exception:
                logger.exception("AI Bio background worker iteration failed")

            await asyncio.sleep(self.settings.worker_poll_interval_seconds)

    async def _process_once(self) -> int:
        processed_count = 0

        for _ in range(self.settings.worker_batch_size):
            job_id = self._claim_next_pending_job_id()

            if job_id is None:
                break

            await self._run_job(job_id)
            processed_count += 1

        return processed_count

    def _claim_next_pending_job_id(self) -> UUID | None:
        db: Session = SessionLocal()

        try:
            statement = (
                select(ConcertIntroductionJob)
                .where(ConcertIntroductionJob.status == "PENDING")
                .order_by(ConcertIntroductionJob.created_at.asc())
                .with_for_update(skip_locked=True)
                .limit(1)
            )

            with db.begin():
                job = db.execute(statement).scalar_one_or_none()

                if job is None:
                    return None

                job.status = "PROCESSING"
                job.processing_stage = "EXTRACTING_TEXT"

                return job.id

        finally:
            db.close()

    async def _run_job(
        self,
        job_id: UUID,
    ) -> None:
        db: Session = SessionLocal()

        try:
            await pipeline_worker_service.run_job_pipeline(
                db=db,
                job_id=job_id,
            )
            logger.info("AI Bio job pipeline completed", extra={"job_id": str(job_id)})
        except Exception:
            logger.exception("AI Bio job pipeline failed", extra={"job_id": str(job_id)})
        finally:
            db.close()


background_worker_service = BackgroundWorkerService()