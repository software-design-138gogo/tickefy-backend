from fastapi import APIRouter, Request

from app.core.config import get_settings
from app.schemas.common import success_response

router = APIRouter(tags=["health"])


def health_payload() -> dict:
    settings = get_settings()
    return {
        "status": "UP",
        "service": settings.service_name,
    }


@router.get("/health")
async def health(request: Request):
    return success_response(
        data=health_payload(),
        request_id=request.state.request_id,
    )


@router.get("/actuator/health")
async def actuator_health(request: Request):
    return success_response(
        data=health_payload(),
        request_id=request.state.request_id,
    )


@router.get("/livez")
async def livez(request: Request):
    return success_response(
        data={"status": "UP"},
        request_id=request.state.request_id,
    )


@router.get("/readyz")
async def readyz(request: Request):
    return success_response(
        data={"status": "READY"},
        request_id=request.state.request_id,
    )
