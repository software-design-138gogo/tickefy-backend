from fastapi import Depends

from app.core.config import get_settings
from app.core.error_codes import ErrorCode
from app.core.exceptions import ForbiddenException
from app.security.dependencies import get_current_user
from app.security.principal import CurrentUser


async def require_dev_endpoint_enabled(
    current_user: CurrentUser = Depends(get_current_user),
) -> CurrentUser:
    settings = get_settings()

    if not settings.dev_endpoints_enabled:
        raise ForbiddenException(
            code=ErrorCode.FORBIDDEN,
            message="Development endpoints are disabled.",
        )

    if not current_user.has_any_role("ADMIN", "ORGANIZER"):
        raise ForbiddenException(
            code=ErrorCode.FORBIDDEN,
            message="Insufficient permissions.",
        )

    return current_user


RequireDevEndpoint = Depends(require_dev_endpoint_enabled)