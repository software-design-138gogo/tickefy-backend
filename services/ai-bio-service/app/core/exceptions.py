from typing import Any

from app.core.error_codes import ErrorCode


class AppException(Exception):
    def __init__(
        self,
        *,
        http_status: int,
        code: ErrorCode | str,
        message: str,
        details: dict[str, Any] | None = None,
    ) -> None:
        self.http_status = http_status
        self.code = str(code)
        self.message = message
        self.details = details or {}
        super().__init__(message)


class BadRequestException(AppException):
    def __init__(
        self,
        code: ErrorCode | str,
        message: str,
        details: dict[str, Any] | None = None,
    ) -> None:
        super().__init__(
            http_status=400,
            code=code,
            message=message,
            details=details,
        )


class UnauthorizedException(AppException):
    def __init__(
        self,
        code: ErrorCode | str = ErrorCode.UNAUTHORIZED,
        message: str = "Authentication is required.",
        details: dict[str, Any] | None = None,
    ) -> None:
        super().__init__(
            http_status=401,
            code=code,
            message=message,
            details=details,
        )


class ForbiddenException(AppException):
    def __init__(
        self,
        code: ErrorCode | str = ErrorCode.FORBIDDEN,
        message: str = "Insufficient permission.",
        details: dict[str, Any] | None = None,
    ) -> None:
        super().__init__(
            http_status=403,
            code=code,
            message=message,
            details=details,
        )


class NotFoundException(AppException):
    def __init__(
        self,
        code: ErrorCode | str = ErrorCode.RESOURCE_NOT_FOUND,
        message: str = "Resource not found.",
        details: dict[str, Any] | None = None,
    ) -> None:
        super().__init__(
            http_status=404,
            code=code,
            message=message,
            details=details,
        )


class ConflictException(AppException):
    def __init__(
        self,
        code: ErrorCode | str = ErrorCode.CONFLICT,
        message: str = "State conflict.",
        details: dict[str, Any] | None = None,
    ) -> None:
        super().__init__(
            http_status=409,
            code=code,
            message=message,
            details=details,
        )


class PayloadTooLargeException(AppException):
    def __init__(
        self,
        code: ErrorCode | str,
        message: str,
        details: dict[str, Any] | None = None,
    ) -> None:
        super().__init__(
            http_status=413,
            code=code,
            message=message,
            details=details,
        )


class UnsupportedMediaTypeException(AppException):
    def __init__(
        self,
        code: ErrorCode | str,
        message: str,
        details: dict[str, Any] | None = None,
    ) -> None:
        super().__init__(
            http_status=415,
            code=code,
            message=message,
            details=details,
        )


class UnprocessableEntityException(AppException):
    def __init__(
        self,
        code: ErrorCode | str,
        message: str,
        details: dict[str, Any] | None = None,
    ) -> None:
        super().__init__(
            http_status=422,
            code=code,
            message=message,
            details=details,
        )


class DependencyUnavailableException(AppException):
    def __init__(
        self,
        code: ErrorCode | str = ErrorCode.SERVICE_UNAVAILABLE,
        message: str = "Service is temporarily unavailable.",
        details: dict[str, Any] | None = None,
    ) -> None:
        super().__init__(
            http_status=503,
            code=code,
            message=message,
            details=details,
        )


# Convenience constructors for AI Bio domain
def source_required() -> BadRequestException:
    return BadRequestException(
        code=ErrorCode.SOURCE_REQUIRED,
        message="At least one valid input source is required.",
    )


def unsupported_source_type(source_type: str | None = None) -> UnsupportedMediaTypeException:
    details = {"sourceType": source_type} if source_type else {}
    return UnsupportedMediaTypeException(
        code=ErrorCode.UNSUPPORTED_SOURCE_TYPE,
        message="The input source type is not supported.",
        details=details,
    )


def invalid_source_type(reason: str | None = None) -> UnsupportedMediaTypeException:
    details = {"reason": reason} if reason else {}
    return UnsupportedMediaTypeException(
        code=ErrorCode.INVALID_SOURCE_TYPE,
        message="The file type does not match the actual content.",
        details=details,
    )


def source_too_large(limit: str | None = None) -> PayloadTooLargeException:
    details = {"limit": limit} if limit else {}
    return PayloadTooLargeException(
        code=ErrorCode.SOURCE_TOO_LARGE,
        message="The input source exceeds the size limit.",
        details=details,
    )


def ai_bio_job_already_active(concert_id: str | None = None) -> ConflictException:
    details = {"concertId": concert_id} if concert_id else {}
    return ConflictException(
        code=ErrorCode.AI_BIO_JOB_ALREADY_ACTIVE,
        message="The concert already has an active AI Bio job.",
        details=details,
    )

def idempotency_key_required() -> BadRequestException:
    return BadRequestException(
        code=ErrorCode.IDEMPOTENCY_KEY_REQUIRED,
        message="Idempotency-Key header is required.",
    )


def idempotency_key_reused_with_different_request() -> ConflictException:
    return ConflictException(
        code=ErrorCode.IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST,
        message="Idempotency key was reused with a different request.",
    )