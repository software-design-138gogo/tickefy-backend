from fastapi import APIRouter, Query, Request, File, UploadFile
from uuid import UUID

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
from app.services.source_storage_service import source_storage_service

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