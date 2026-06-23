import logging
from typing import Any

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from sqlalchemy.exc import IntegrityError, SQLAlchemyError
from starlette.exceptions import HTTPException as StarletteHTTPException

from app.core.error_codes import ErrorCode
from app.core.exceptions import AppException
from app.schemas.common import error_response

logger = logging.getLogger(__name__)


def get_request_id(request: Request) -> str:
    return getattr(request.state, "request_id", None) or request.headers.get("X-Request-ID") or "req-unknown"


def json_error(
    *,
    request: Request,
    http_status: int,
    code: ErrorCode | str,
    message: str,
    details: dict[str, Any] | None = None,
) -> JSONResponse:
    request_id = get_request_id(request)
    body = error_response(
        http_status=http_status,
        code=str(code),
        message=message,
        request_id=request_id,
        details=details,
    )
    return JSONResponse(
        status_code=http_status,
        content=body,
        headers={"X-Request-ID": request_id},
    )


def normalize_validation_errors(exc: RequestValidationError) -> dict[str, Any]:
    details: dict[str, Any] = {}

    for err in exc.errors():
        loc = err.get("loc", [])
        msg = err.get("msg", "Invalid value")

        # loc examples:
        # ("body", "email")
        # ("query", "page")
        # ("path", "concertId")
        if len(loc) >= 2:
            field = ".".join(str(part) for part in loc[1:])
        elif loc:
            field = str(loc[0])
        else:
            field = "request"

        details[field] = msg

    return details


def register_exception_handlers(app: FastAPI) -> None:
    @app.exception_handler(AppException)
    async def app_exception_handler(request: Request, exc: AppException) -> JSONResponse:
        logger.info(
            "Handled domain exception",
            extra={
                "request_id": get_request_id(request),
                "error_code": exc.code,
                "http_status": exc.http_status,
            },
        )
        return json_error(
            request=request,
            http_status=exc.http_status,
            code=exc.code,
            message=exc.message,
            details=exc.details,
        )

    @app.exception_handler(RequestValidationError)
    async def validation_exception_handler(
        request: Request,
        exc: RequestValidationError,
    ) -> JSONResponse:
        return json_error(
            request=request,
            http_status=400,
            code=ErrorCode.VALIDATION_ERROR,
            message="Invalid request data.",
            details=normalize_validation_errors(exc),
        )

    @app.exception_handler(StarletteHTTPException)
    async def http_exception_handler(
        request: Request,
        exc: StarletteHTTPException,
    ) -> JSONResponse:
        if exc.status_code == 404:
            return json_error(
                request=request,
                http_status=404,
                code=ErrorCode.RESOURCE_NOT_FOUND,
                message="Resource not found.",
            )

        if exc.status_code == 405:
            return json_error(
                request=request,
                http_status=405,
                code=ErrorCode.VALIDATION_ERROR,
                message="HTTP method is not supported for this endpoint.",
            )

        if exc.status_code == 401:
            return json_error(
                request=request,
                http_status=401,
                code=ErrorCode.UNAUTHORIZED,
                message="Authentication is required.",
            )

        if exc.status_code == 403:
            return json_error(
                request=request,
                http_status=403,
                code=ErrorCode.FORBIDDEN,
                message="Insufficient permission.",
            )

        return json_error(
            request=request,
            http_status=exc.status_code,
            code=ErrorCode.INTERNAL_SERVER_ERROR if exc.status_code >= 500 else ErrorCode.VALIDATION_ERROR,
            message="Invalid request." if exc.status_code < 500 else "Internal server error.",
        )

    @app.exception_handler(IntegrityError)
    async def integrity_error_handler(request: Request, exc: IntegrityError) -> JSONResponse:
        logger.warning(
            "Database integrity error",
            extra={"request_id": get_request_id(request)},
            exc_info=True,
        )

        # Do not leak constraints or raw SQL to the client.
        return json_error(
            request=request,
            http_status=409,
            code=ErrorCode.CONFLICT,
            message="State conflict.",
        )

    @app.exception_handler(SQLAlchemyError)
    async def sqlalchemy_error_handler(request: Request, exc: SQLAlchemyError) -> JSONResponse:
        logger.error(
            "Database error",
            extra={"request_id": get_request_id(request)},
            exc_info=True,
        )
        return json_error(
            request=request,
            http_status=503,
            code=ErrorCode.SERVICE_UNAVAILABLE,
            message="Service is temporarily unavailable.",
        )

    @app.exception_handler(Exception)
    async def unhandled_exception_handler(request: Request, exc: Exception) -> JSONResponse:
        logger.exception(
            "Unhandled exception",
            extra={"request_id": get_request_id(request)},
        )
        return json_error(
            request=request,
            http_status=500,
            code=ErrorCode.INTERNAL_SERVER_ERROR,
            message="Internal server error.",
        )
