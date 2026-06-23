from datetime import datetime, timezone
from uuid import UUID

from fastapi import FastAPI, Header, Request
from fastapi.responses import JSONResponse

app = FastAPI(title="Mock Event Service for AI Bio")


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def success_response(data: dict, request_id: str) -> dict:
    return {
        "success": True,
        "data": data,
        "error": None,
        "requestId": request_id,
        "timestamp": now_iso(),
    }


def error_response(status: int, code: str, message: str, request_id: str) -> JSONResponse:
    return JSONResponse(
        status_code=status,
        content={
            "success": False,
            "data": None,
            "error": {
                "httpStatus": status,
                "code": code,
                "message": message,
                "details": {},
            },
            "requestId": request_id,
            "timestamp": now_iso(),
        },
        headers={"X-Request-ID": request_id},
    )


@app.get("/internal/concerts/{concert_id}/ai-context")
async def get_ai_context(
    concert_id: UUID,
    request: Request,
    authorization: str | None = Header(default=None),
):
    request_id = request.headers.get("X-Request-ID", "req-mock-event-service")

    if not authorization or not authorization.startswith("Bearer "):
        return error_response(
            status=401,
            code="UNAUTHORIZED",
            message="Authentication is required.",
            request_id=request_id,
        )

    if str(concert_id).startswith("00000000"):
        return error_response(
            status=404,
            code="CONCERT_NOT_FOUND",
            message="Concert not found.",
            request_id=request_id,
        )

    if str(concert_id).startswith("ffffffff"):
        return error_response(
            status=403,
            code="CONCERT_ACCESS_DENIED",
            message="You do not have permission to manage this concert.",
            request_id=request_id,
        )

    return success_response(
        data={
            "concertId": str(concert_id),
            "concertName": "Mock Concert",
            "organizerId": "33333333-3333-3333-3333-333333333333",
            "status": "DRAFT",
            "currentIntroductionUpdatedAt": None,
            "manualIntroductionUpdatedAt": None,
        },
        request_id=request_id,
    )