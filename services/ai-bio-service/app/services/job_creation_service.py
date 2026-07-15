import hashlib
import json
from datetime import datetime, timedelta, timezone
from uuid import UUID, uuid4

from fastapi import UploadFile
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.core.error_codes import ErrorCode
from app.core.exceptions import (
    BadRequestException,
    ConflictException,
    ai_bio_job_already_active,
    idempotency_key_required,
    idempotency_key_reused_with_different_request,
)
from app.db.models import ConcertIntroductionJob, IdempotencyRecord, SourceDocument
from app.integrations.event_service_client import event_service_client
from app.schemas.job import CreateAiBioJobResponse
from app.security.principal import CurrentUser
from app.sources.source_type import SOURCE_TYPE_TO_EXTENSION
from app.sources.source_validator import ValidatedSource, source_validator
from app.storage.object_storage_client import object_storage_client


ALLOWED_CONCERT_STATUSES = {"DRAFT", "PUBLISHED"}
ALLOWED_TARGET_LENGTHS = {"SHORT", "MEDIUM", "LONG"}


class JobCreationService:
    async def create_job(
        self,
        *,
        db: Session,
        concert_id: UUID,
        files: list[UploadFile] | None,
        language: str,
        target_length: str,
        tone: str | None,
        idempotency_key: str | None,
        current_user: CurrentUser,
        request_id: str,
    ) -> tuple[CreateAiBioJobResponse, int]:
        normalized_idempotency_key = self._normalize_idempotency_key(idempotency_key)
        normalized_language = self._normalize_language(language)
        normalized_target_length = self._normalize_target_length(target_length)
        normalized_tone = self._normalize_tone(tone)

        validated_sources = await source_validator.validate_files(files)

        request_hash = self._build_request_hash(
            concert_id=concert_id,
            language=normalized_language,
            target_length=normalized_target_length,
            tone=normalized_tone,
            sources=validated_sources,
        )

        existing_idempotency_record = self._find_idempotency_record(
            db=db,
            current_user=current_user,
            idempotency_key=normalized_idempotency_key,
        )

        if existing_idempotency_record is not None:
            return self._handle_idempotency_replay(
                db=db,
                record=existing_idempotency_record,
                request_hash=request_hash,
            )

        context = await event_service_client.get_ai_context(
            concert_id=concert_id,
            bearer_token=current_user.token,
            request_id=request_id,
        )

        self._check_concert_permission(
            current_user=current_user,
            organizer_id=context.organizer_id,
            concert_id=concert_id,
        )

        self._check_concert_status(context.status)

        active_job = self._find_active_job(
            db=db,
            concert_id=concert_id,
        )

        if active_job is not None:
            raise ai_bio_job_already_active(str(concert_id))

        job_id = uuid4()
        now = self._utc_now()
        created_by_role = self._resolve_actor_role(current_user)

        stored_sources = await self._store_sources(
            concert_id=concert_id,
            job_id=job_id,
            sources=validated_sources,
        )

        response = CreateAiBioJobResponse(
            jobId=str(job_id),
            concertId=str(concert_id),
            status="PENDING",
            processingStage="RECEIVED",
            replayDetected=False,
            sourceCount=len(stored_sources),
            language=normalized_language,
            targetLength=normalized_target_length,
            tone=normalized_tone,
            createdAt=self._to_iso(now),
        )

        job = ConcertIntroductionJob(
            id=job_id,
            concert_id=concert_id,
            concert_name_snapshot=context.concert_name,
            organizer_id_snapshot=context.organizer_id,
            created_by=current_user.user_id,
            created_by_role=created_by_role,
            correlation_id=request_id,
            idempotency_key=normalized_idempotency_key,
            status="PENDING",
            processing_stage="RECEIVED",
            language=normalized_language,
            target_length=normalized_target_length,
            tone=normalized_tone,
            requested_at=now,
            created_at=now,
            updated_at=now,
        )

        source_rows = [
            SourceDocument(
                id=item["source_id"],
                job_id=job_id,
                source_type=item["source"].source_type.value,
                status="STORED",
                original_file_name=item["source"].original_file_name,
                content_type=item["source"].content_type,
                file_size_bytes=item["source"].file_size_bytes,
                checksum_sha256=item["source"].checksum_sha256,
                object_key=item["object_key"],
                created_at=now,
                updated_at=now,
            )
            for item in stored_sources
        ]

        idempotency_record = IdempotencyRecord(
            created_by=current_user.user_id,
            idempotency_key=normalized_idempotency_key,
            action="CREATE_JOB",
            request_hash=request_hash,
            response_status=202,
            response_body=response.model_dump(mode="json"),
            job_id=job_id,
            replay_count=0,
            expires_at=now + timedelta(hours=24),
            created_at=now,
            updated_at=now,
        )

        try:
            db.add(job)
            db.add_all(source_rows)
            db.add(idempotency_record)
            db.commit()
        except IntegrityError as exc:
            db.rollback()
            message = str(exc.orig)

            if "uq_ai_bio_active_job_per_concert" in message:
                raise ai_bio_job_already_active(str(concert_id)) from exc

            if "uq_ai_bio_idempotency_records_scope" in message:
                raise idempotency_key_reused_with_different_request() from exc

            raise ConflictException(
                code=ErrorCode.CONFLICT,
                message="A conflicting AI Bio job request already exists.",
            ) from exc

        return response, 202

    def _normalize_idempotency_key(self, idempotency_key: str | None) -> str:
        if idempotency_key is None or not idempotency_key.strip():
            raise idempotency_key_required()

        normalized = idempotency_key.strip()

        if len(normalized) > 200:
            raise BadRequestException(
                code=ErrorCode.VALIDATION_ERROR,
                message="Idempotency-Key is too long.",
                details={"maxLength": 200},
            )

        return normalized

    def _normalize_language(self, language: str | None) -> str:
        normalized = (language or "vi").strip().lower()

        if normalized not in {"vi", "en"}:
            raise BadRequestException(
                code=ErrorCode.VALIDATION_ERROR,
                message="Language is not supported.",
                details={"allowedValues": ["vi", "en"]},
            )

        return normalized

    def _normalize_target_length(self, target_length: str | None) -> str:
        normalized = (target_length or "SHORT").strip().upper()

        if normalized not in ALLOWED_TARGET_LENGTHS:
            raise BadRequestException(
                code=ErrorCode.VALIDATION_ERROR,
                message="Target length is not supported.",
                details={"allowedValues": sorted(ALLOWED_TARGET_LENGTHS)},
            )

        return normalized

    def _normalize_tone(self, tone: str | None) -> str | None:
        if tone is None or not tone.strip():
            return None

        normalized = tone.strip().upper()
        allowed_tones = {"PROFESSIONAL", "ENERGETIC", "LUXURY", "FRIENDLY"}

        if normalized not in allowed_tones:
            raise BadRequestException(
                code=ErrorCode.VALIDATION_ERROR,
                message="Tone is not supported.",
                details={"allowedValues": sorted(allowed_tones)},
            )

        return normalized

    def _build_request_hash(
        self,
        *,
        concert_id: UUID,
        language: str,
        target_length: str,
        tone: str | None,
        sources: list[ValidatedSource],
    ) -> str:
        payload = {
            "concertId": str(concert_id),
            "language": language,
            "targetLength": target_length,
            "tone": tone,
            "sources": [
                {
                    "sourceType": source.source_type.value,
                    "originalFileName": source.original_file_name,
                    "contentType": source.content_type,
                    "fileSizeBytes": source.file_size_bytes,
                    "checksumSha256": source.checksum_sha256,
                }
                for source in sources
            ],
        }

        serialized = json.dumps(payload, sort_keys=True, separators=(",", ":"))
        return hashlib.sha256(serialized.encode("utf-8")).hexdigest()

    def _find_idempotency_record(
        self,
        *,
        db: Session,
        current_user: CurrentUser,
        idempotency_key: str,
    ) -> IdempotencyRecord | None:
        statement = select(IdempotencyRecord).where(
            IdempotencyRecord.created_by == current_user.user_id,
            IdempotencyRecord.idempotency_key == idempotency_key,
            IdempotencyRecord.action == "CREATE_JOB",
        )

        return db.execute(statement).scalar_one_or_none()

    def _handle_idempotency_replay(
        self,
        *,
        db: Session,
        record: IdempotencyRecord,
        request_hash: str,
    ) -> tuple[CreateAiBioJobResponse, int]:
        if record.request_hash != request_hash:
            raise idempotency_key_reused_with_different_request()

        if not record.response_body:
            raise ConflictException(
                code=ErrorCode.CONFLICT,
                message="Idempotency record exists but has no stored response.",
            )

        response_body = dict(record.response_body)
        response_body["replayDetected"] = True

        record.replay_count += 1
        record.updated_at = self._utc_now()
        db.commit()

        return CreateAiBioJobResponse.model_validate(response_body), record.response_status or 202

    def _check_concert_permission(
        self,
        *,
        current_user: CurrentUser,
        organizer_id: UUID,
        concert_id: UUID,
    ) -> None:
        if current_user.has_role("ADMIN"):
            return

        if current_user.user_id != organizer_id:
            raise ConflictException(
                code=ErrorCode.CONCERT_ACCESS_DENIED,
                message="You do not have permission to manage this concert.",
                details={"concertId": str(concert_id)},
            )

    def _check_concert_status(self, status: str) -> None:
        normalized_status = status.strip().upper()

        if normalized_status not in ALLOWED_CONCERT_STATUSES:
            raise ConflictException(
                code=ErrorCode.CONFLICT,
                message="Concert status does not allow AI Bio generation.",
                details={
                    "status": normalized_status,
                    "allowedStatuses": sorted(ALLOWED_CONCERT_STATUSES),
                },
            )

    def _find_active_job(
        self,
        *,
        db: Session,
        concert_id: UUID,
    ) -> ConcertIntroductionJob | None:
        statement = select(ConcertIntroductionJob).where(
            ConcertIntroductionJob.concert_id == concert_id,
            ConcertIntroductionJob.status.in_(["PENDING", "PROCESSING"]),
        )

        return db.execute(statement).scalar_one_or_none()

    async def _store_sources(
        self,
        *,
        concert_id: UUID,
        job_id: UUID,
        sources: list[ValidatedSource],
    ) -> list[dict]:
        stored_sources: list[dict] = []

        for source in sources:
            source_id = uuid4()
            extension = SOURCE_TYPE_TO_EXTENSION[source.source_type]

            object_key = (
                f"ai-bio/{concert_id}/jobs/{job_id}"
                f"/sources/{source_id}{extension}"
            )

            await object_storage_client.put_bytes(
                object_key=object_key,
                content=source.content,
                content_type=source.content_type,
            )

            stored_sources.append(
                {
                    "source_id": source_id,
                    "source": source,
                    "object_key": object_key,
                }
            )

        return stored_sources

    def _resolve_actor_role(self, current_user: CurrentUser) -> str:
        if current_user.has_role("ADMIN"):
            return "ADMIN"

        return "ORGANIZER"

    def _utc_now(self) -> datetime:
        return datetime.now(timezone.utc)

    def _to_iso(self, value: datetime) -> str:
        return value.isoformat().replace("+00:00", "Z")


job_creation_service = JobCreationService()