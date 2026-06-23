from datetime import datetime, timezone
from uuid import UUID

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core.error_codes import ErrorCode
from app.core.exceptions import ConflictException, NotFoundException
from app.db.models import ConcertIntroductionJob, DocumentExtraction, SourceDocument
from app.extractors.document_extractor import DocumentExtractionError, document_extractor
from app.schemas.extraction import ExtractedDocumentItem, ExtractionJobResponse
from app.sources.source_type import SourceType
from app.storage.object_storage_client import object_storage_client


class ExtractionWorkerService:
    async def extract_job_sources(
        self,
        *,
        db: Session,
        job_id: UUID,
    ) -> ExtractionJobResponse:
        job = self._get_job(db=db, job_id=job_id)

        if job.status == "SUCCEEDED":
            raise ConflictException(
                code=ErrorCode.CONFLICT,
                message="Succeeded jobs cannot be extracted again.",
                details={"jobId": str(job_id)},
            )

        if job.status == "FAILED" and not job.is_retryable:
            raise ConflictException(
                code=ErrorCode.AI_BIO_JOB_NOT_RETRYABLE,
                message="Job is not retryable.",
                details={"jobId": str(job_id)},
            )

        sources = self._get_sources(db=db, job_id=job_id)

        if not sources:
            raise ConflictException(
                code=ErrorCode.NO_USABLE_SOURCE_CONTENT,
                message="Job has no source documents.",
                details={"jobId": str(job_id)},
            )

        now = self._utc_now()
        job.status = "PROCESSING"
        job.processing_stage = "EXTRACTING_TEXT"
        job.started_at = job.started_at or now
        job.updated_at = now
        db.commit()

        documents: list[ExtractedDocumentItem] = []
        extracted_count = 0
        failed_count = 0

        for source in sources:
            item = await self._extract_one_source(
                db=db,
                source=source,
            )

            documents.append(item)

            if item.status == "EXTRACTED":
                extracted_count += 1
            else:
                failed_count += 1

        now = self._utc_now()

        if extracted_count == 0:
            job.status = "FAILED"
            job.processing_stage = "EXTRACTING_TEXT"
            job.error_code = ErrorCode.NO_USABLE_SOURCE_CONTENT
            job.error_message = "No usable text content was found in any source."
            job.is_retryable = False
            job.failed_at = now
            job.updated_at = now
        else:
            job.status = "PROCESSING"
            job.processing_stage = "CLEANING_TEXT"
            job.warning_count = failed_count
            job.updated_at = now

        db.commit()

        return ExtractionJobResponse(
            jobId=str(job.id),
            concertId=str(job.concert_id),
            status=job.status,
            processingStage=job.processing_stage,
            totalSources=len(sources),
            extractedCount=extracted_count,
            failedCount=failed_count,
            documents=documents,
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
                code=ErrorCode.RESOURCE_NOT_FOUND,
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

    async def _extract_one_source(
        self,
        *,
        db: Session,
        source: SourceDocument,
    ) -> ExtractedDocumentItem:
        now = self._utc_now()

        source.status = "EXTRACTION_PENDING"
        source.updated_at = now
        db.commit()

        try:
            if not source.object_key:
                raise DocumentExtractionError(
                    code=ErrorCode.NO_USABLE_SOURCE_CONTENT,
                    message="Source document has no object key.",
                    retryable=False,
                )

            content = await object_storage_client.get_bytes(
                object_key=source.object_key,
            )

            result = document_extractor.extract(
                source_type=SourceType(source.source_type),
                content=content,
            )

            extraction = self._get_or_create_extraction(
                db=db,
                source_id=source.id,
            )

            now = self._utc_now()

            extraction.extracted_text = result.extracted_text
            extraction.cleaned_text = result.cleaned_text
            extraction.extracted_char_count = result.extracted_char_count
            extraction.cleaned_char_count = result.cleaned_char_count
            extraction.parser_name = result.parser_name
            extraction.parser_version = result.parser_version
            extraction.warnings = result.warnings
            extraction.extra_metadata = {}
            extraction.extraction_completed_at = now
            extraction.updated_at = now

            source.status = "EXTRACTED"
            source.extraction_error_code = None
            source.extraction_error_message = None
            source.warning_count = len(result.warnings)
            source.updated_at = now

            db.commit()

            return ExtractedDocumentItem(
                sourceDocumentId=str(source.id),
                sourceType=source.source_type,
                status="EXTRACTED",
                extractedCharCount=result.extracted_char_count,
                cleanedCharCount=result.cleaned_char_count,
                errorCode=None,
            )

        except DocumentExtractionError as exc:
            now = self._utc_now()

            source.status = "FAILED"
            source.extraction_error_code = exc.code
            source.extraction_error_message = exc.message
            source.updated_at = now

            db.commit()

            return ExtractedDocumentItem(
                sourceDocumentId=str(source.id),
                sourceType=source.source_type,
                status="FAILED",
                extractedCharCount=0,
                cleanedCharCount=0,
                errorCode=exc.code,
            )

    def _get_or_create_extraction(
        self,
        *,
        db: Session,
        source_id: UUID,
    ) -> DocumentExtraction:
        statement = select(DocumentExtraction).where(
            DocumentExtraction.document_id == source_id,
        )

        extraction = db.execute(statement).scalar_one_or_none()

        if extraction is not None:
            return extraction

        now = self._utc_now()

        extraction = DocumentExtraction(
            document_id=source_id,
            extracted_text=None,
            cleaned_text=None,
            extracted_char_count=0,
            cleaned_char_count=0,
            parser_name=None,
            parser_version=None,
            warnings=[],
            extra_metadata={},
            extraction_started_at=now,
            created_at=now,
            updated_at=now,
        )

        db.add(extraction)
        db.flush()

        return extraction

    def _utc_now(self) -> datetime:
        return datetime.now(timezone.utc)


extraction_worker_service = ExtractionWorkerService()