from fastapi import APIRouter, Request
from app.core.config import get_settings
from app.schemas.common import success_response

router = APIRouter(tags=["health"])


@router.get("/actuator/health")
async def actuator_health(request: Request):
    settings = get_settings()
    return success_response(
        data={
            "status": "UP",
            "service": settings.service_name,
        },
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
