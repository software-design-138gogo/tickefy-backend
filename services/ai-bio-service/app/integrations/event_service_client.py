import asyncio
import logging
from uuid import UUID

import httpx
from pydantic import ValidationError

from app.core.config import get_settings
from app.core.error_codes import ErrorCode
from app.core.exceptions import (
    DependencyUnavailableException,
    ForbiddenException,
    NotFoundException,
    UnauthorizedException,
)
from app.schemas.event_service import AiConcertContextResponse

logger = logging.getLogger(__name__)


class EventServiceClient:
    def __init__(self) -> None:
        self.settings = get_settings()

    async def get_ai_context(
        self,
        *,
        concert_id: UUID,
        bearer_token: str,
        request_id: str,
    ) -> AiConcertContextResponse:
        url = f"{self.settings.event_service_url}/internal/concerts/{concert_id}/ai-context"

        headers = {
            "Authorization": f"Bearer {bearer_token}",
            "X-Request-ID": request_id,
            "Accept": "application/json",
        }

        timeout = httpx.Timeout(
            connect=self.settings.event_service_connect_timeout_seconds,
            read=self.settings.event_service_read_timeout_seconds,
            write=self.settings.event_service_read_timeout_seconds,
            pool=self.settings.event_service_connect_timeout_seconds,
        )

        last_error: Exception | None = None

        for attempt in range(1, self.settings.event_service_max_attempts + 1):
            try:
                async with httpx.AsyncClient(timeout=timeout) as client:
                    response = await client.get(url, headers=headers)

                return self._handle_response(response=response, concert_id=concert_id)

            except (
                httpx.ConnectError,
                httpx.ConnectTimeout,
                httpx.ReadTimeout,
                httpx.RemoteProtocolError,
            ) as exc:
                last_error = exc
                logger.warning(
                    "Event Service request failed",
                    extra={
                        "request_id": request_id,
                        "concert_id": str(concert_id),
                        "attempt": attempt,
                    },
                )

                if attempt < self.settings.event_service_max_attempts:
                    await asyncio.sleep(0.2 * attempt)
                    continue

            except DependencyUnavailableException:
                raise

        raise DependencyUnavailableException(
            code=ErrorCode.EVENT_SERVICE_UNAVAILABLE,
            message="Event Service is temporarily unavailable.",
            details={
                "concertId": str(concert_id),
                "reason": type(last_error).__name__ if last_error else "unknown",
            },
        )

    def _handle_response(
        self,
        *,
        response: httpx.Response,
        concert_id: UUID,
    ) -> AiConcertContextResponse:
        if response.status_code == 401:
            raise UnauthorizedException(
                code=ErrorCode.INVALID_TOKEN,
                message="Invalid session.",
            )

        if response.status_code == 403:
            raise ForbiddenException(
                code=ErrorCode.CONCERT_ACCESS_DENIED,
                message="You do not have permission to manage this concert.",
                details={"concertId": str(concert_id)},
            )

        if response.status_code == 404:
            raise NotFoundException(
                code=ErrorCode.CONCERT_NOT_FOUND,
                message="Concert not found.",
                details={"concertId": str(concert_id)},
            )

        if response.status_code >= 500:
            raise DependencyUnavailableException(
                code=ErrorCode.EVENT_SERVICE_UNAVAILABLE,
                message="Event Service is temporarily unavailable.",
                details={
                    "concertId": str(concert_id),
                    "statusCode": response.status_code,
                },
            )

        if response.status_code < 200 or response.status_code >= 300:
            raise DependencyUnavailableException(
                code=ErrorCode.EVENT_SERVICE_UNAVAILABLE,
                message="Event Service returned an unexpected response.",
                details={
                    "concertId": str(concert_id),
                    "statusCode": response.status_code,
                },
            )

        try:
            body = response.json()
        except ValueError as exc:
            raise DependencyUnavailableException(
                code=ErrorCode.EVENT_SERVICE_UNAVAILABLE,
                message="Event Service returned invalid JSON.",
                details={"concertId": str(concert_id)},
            ) from exc

        data = self._extract_data(body)

        try:
            return AiConcertContextResponse.model_validate(data)
        except ValidationError as exc:
            raise DependencyUnavailableException(
                code=ErrorCode.EVENT_SERVICE_UNAVAILABLE,
                message="Event Service response contract is invalid.",
                details={
                    "concertId": str(concert_id),
                    "validationError": exc.errors(),
                },
            ) from exc

    def _extract_data(self, body: dict) -> dict:
        if "success" not in body:
            return body

        if body.get("success") is True:
            data = body.get("data")
            if isinstance(data, dict):
                return data

        error = body.get("error") or {}
        code = error.get("code")
        message = error.get("message") or "Event Service request failed."

        if code == ErrorCode.CONCERT_NOT_FOUND:
            raise NotFoundException(
                code=ErrorCode.CONCERT_NOT_FOUND,
                message=message,
            )

        if code in {ErrorCode.CONCERT_ACCESS_DENIED, ErrorCode.FORBIDDEN}:
            raise ForbiddenException(
                code=ErrorCode.CONCERT_ACCESS_DENIED,
                message=message,
            )

        if code in {ErrorCode.UNAUTHORIZED, ErrorCode.INVALID_TOKEN}:
            raise UnauthorizedException(
                code=ErrorCode.INVALID_TOKEN,
                message="Invalid session.",
            )

        raise DependencyUnavailableException(
            code=ErrorCode.EVENT_SERVICE_UNAVAILABLE,
            message="Event Service request failed.",
            details={"upstreamCode": code},
        )


event_service_client = EventServiceClient()