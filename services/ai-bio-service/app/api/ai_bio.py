from fastapi import APIRouter, Depends, File, Form, Header, Query, Request, UploadFile
from fastapi.responses import JSONResponse
from uuid import UUID
from sqlalchemy.orm import Session

from app.core.error_codes import ErrorCode
from app.core.exceptions import (
    BadRequestException,
    DependencyUnavailableException,
    NotFoundException,
    ai_bio_job_already_active,
    source_required,
    source_too_large,
    unsupported_source_type,
)
from app.schemas.common import success_response
from app.security.dependencies import RequireAuthenticated, RequireOrganizerOrAdmin
from app.security.principal import CurrentUser
from app.integrations.event_service_client import event_service_client
from app.services.job_creation_service import job_creation_service
from app.services.source_storage_service import source_storage_service
from app.services.generation_service import generation_service
from app.services.outbox_publisher_service import outbox_publisher_service
from app.services.pipeline_worker_service import pipeline_worker_service
from app.db.session import get_db
from app.services.extraction_worker_service import extraction_worker_service

router = APIRouter(prefix="/api/ai-bio", tags=["ai-bio"])


@router.get("/_info")
async def service_info(request: Request):
    return success_response(
        data={
            "service": "ai-bio-service",
            "status": "SKELETON_READY",
            "runtime": "python-fastapi",
        },
        request_id=request.state.request_id,
    )


@router.get("/_dev/validation")
async def validation_demo(
    request: Request,
    page: int = Query(0, ge=0),
    size: int = Query(20, ge=1, le=100),
):
    return success_response(
        data={
            "page": page,
            "size": size,
        },
        request_id=request.state.request_id,
    )

@router.post("/concerts/{concert_id}/jobs")
async def create_ai_bio_job(
    concert_id: UUID,
    request: Request,
    files: list[UploadFile] | None = File(default=None),
    language: str = Form(default="vi"),
    target_length: str = Form(default="SHORT", alias="targetLength"),
    tone: str | None = Form(default=None),
    idempotency_key: str | None = Header(default=None, alias="Idempotency-Key"),
    current_user: CurrentUser = RequireOrganizerOrAdmin,
    db: Session = Depends(get_db),
):
    result, http_status = await job_creation_service.create_job(
        db=db,
        concert_id=concert_id,
        files=files,
        language=language,
        target_length=target_length,
        tone=tone,
        idempotency_key=idempotency_key,
        current_user=current_user,
        request_id=request.state.request_id,
    )

    return JSONResponse(
        status_code=http_status,
        content=success_response(
            data=result.model_dump(mode="json"),
            request_id=request.state.request_id,
        ),
        headers={"X-Request-ID": request.state.request_id},
    )


@router.get("/_dev/error/{code}")
async def error_demo(code: str):
    if code == "source-required":
        raise source_required()

    if code == "unsupported-source-type":
        raise unsupported_source_type("exe")

    if code == "source-too-large":
        raise source_too_large("10MB/file")

    if code == "active-job":
        raise ai_bio_job_already_active("11111111-1111-1111-1111-111111111111")

    if code == "concert-not-found":
        raise NotFoundException(
            code=ErrorCode.CONCERT_NOT_FOUND,
            message="Concert not found.",
        )

    if code == "event-unavailable":
        raise DependencyUnavailableException(
            code=ErrorCode.EVENT_SERVICE_UNAVAILABLE,
            message="Event Service is temporarily unavailable.",
        )

    raise BadRequestException(
        code=ErrorCode.VALIDATION_ERROR,
        message="Invalid demo error code.",
        details={"allowed": [
            "source-required",
            "unsupported-source-type",
            "source-too-large",
            "active-job",
            "concert-not-found",
            "event-unavailable",
        ]},
    )


@router.get("/_me")
async def me(
    request: Request,
    current_user: CurrentUser = RequireAuthenticated,
):
    return success_response(
        data={
            "userId": str(current_user.user_id),
            "email": current_user.email,
            "roles": sorted(current_user.roles),
        },
        request_id=request.state.request_id,
    )


@router.get("/_organizer-check")
async def organizer_check(
    request: Request,
    current_user: CurrentUser = RequireOrganizerOrAdmin,
):
    return success_response(
        data={
            "allowed": True,
            "userId": str(current_user.user_id),
            "roles": sorted(current_user.roles),
        },
        request_id=request.state.request_id,
    )

@router.get("/_dev/concerts/{concert_id}/ai-context")
async def get_ai_context_demo(
    concert_id: UUID,
    request: Request,
    current_user: CurrentUser = RequireOrganizerOrAdmin,
):
    context = await event_service_client.get_ai_context(
        concert_id=concert_id,
        bearer_token=current_user.token,
        request_id=request.state.request_id,
    )

    return success_response(
        data=context.model_dump(mode="json", by_alias=True),
        request_id=request.state.request_id,
    )

@router.post("/_dev/concerts/{concert_id}/sources")
async def validate_and_store_sources_demo(
    concert_id: UUID,
    request: Request,
    files: list[UploadFile] = File(default=[]),
    current_user: CurrentUser = RequireOrganizerOrAdmin,
):
    result = await source_storage_service.validate_and_store_files(
        concert_id=concert_id,
        files=files,
    )

    return success_response(
        data=result.model_dump(mode="json"),
        request_id=request.state.request_id,
    )

@router.post("/_dev/jobs/{job_id}/extract")
async def extract_job_sources_demo(
    job_id: UUID,
    request: Request,
    current_user: CurrentUser = RequireOrganizerOrAdmin,
    db: Session = Depends(get_db),
):
    result = await extraction_worker_service.extract_job_sources(
        db=db,
        job_id=job_id,
    )

    return success_response(
        data=result.model_dump(mode="json"),
        request_id=request.state.request_id,
    )

@router.post("/_dev/jobs/{job_id}/generate")
async def generate_job_introduction_demo(
    job_id: UUID,
    request: Request,
    current_user: CurrentUser = RequireOrganizerOrAdmin,
    db: Session = Depends(get_db),
):
    result = await generation_service.generate_introduction(
        db=db,
        job_id=job_id,
    )

    return success_response(
        data=result.model_dump(mode="json"),
        request_id=request.state.request_id,
    )

@router.post("/_dev/outbox/publish")
async def publish_outbox_events_demo(
    request: Request,
    limit: int | None = Query(default=None, ge=1, le=100),
    current_user: CurrentUser = RequireOrganizerOrAdmin,
    db: Session = Depends(get_db),
):
    result = await outbox_publisher_service.publish_pending_events(
        db=db,
        limit=limit,
    )

    return success_response(
        data=result.model_dump(mode="json"),
        request_id=request.state.request_id,
    )

@router.post("/_dev/jobs/{job_id}/run-pipeline")
async def run_job_pipeline_demo(
    job_id: UUID,
    request: Request,
    current_user: CurrentUser = RequireOrganizerOrAdmin,
    db: Session = Depends(get_db),
):
    result = await pipeline_worker_service.run_job_pipeline(
        db=db,
        job_id=job_id,
    )

    return success_response(
        data=result.model_dump(mode="json"),
        request_id=request.state.request_id,
    )


@router.post("/_dev/jobs/run-next-pending")
async def run_next_pending_job_demo(
    request: Request,
    current_user: CurrentUser = RequireOrganizerOrAdmin,
    db: Session = Depends(get_db),
):
    result = await pipeline_worker_service.run_next_pending_job(
        db=db,
    )

    return success_response(
        data=result.model_dump(mode="json"),
        request_id=request.state.request_id,
    )