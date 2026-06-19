from datetime import datetime, timezone
from typing import Any

from pydantic import BaseModel, Field


def utc_now_iso() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


class ErrorBody(BaseModel):
    httpStatus: int
    code: str
    message: str
    details: dict[str, Any] = Field(default_factory=dict)


class ApiResponse(BaseModel):
    success: bool
    data: Any | None
    error: ErrorBody | None
    requestId: str
    timestamp: str


def success_response(data: Any, request_id: str) -> dict[str, Any]:
    return {
        "success": True,
        "data": data,
        "error": None,
        "requestId": request_id,
        "timestamp": utc_now_iso(),
    }


def error_response(
    *,
    http_status: int,
    code: str,
    message: str,
    request_id: str,
    details: dict[str, Any] | None = None,
) -> dict[str, Any]:
    return {
        "success": False,
        "data": None,
        "error": {
            "httpStatus": http_status,
            "code": code,
            "message": message,
            "details": details or {},
        },
        "requestId": request_id,
        "timestamp": utc_now_iso(),
    }
