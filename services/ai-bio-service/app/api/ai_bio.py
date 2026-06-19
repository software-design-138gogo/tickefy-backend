from fastapi import APIRouter, Request
from app.schemas.common import success_response

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
