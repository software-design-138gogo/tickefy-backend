from collections.abc import Callable

from fastapi import Depends, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from app.core.error_codes import ErrorCode
from app.core.exceptions import ForbiddenException, UnauthorizedException
from app.security.jwt_verifier import jwt_verifier
from app.security.principal import CurrentUser

bearer_scheme = HTTPBearer(auto_error=False)


async def get_current_user(
    request: Request,
    credentials: HTTPAuthorizationCredentials | None = Depends(bearer_scheme),
) -> CurrentUser:
    if credentials is None:
        raise UnauthorizedException(
            code=ErrorCode.UNAUTHORIZED,
            message="Authentication required.",
        )

    if credentials.scheme.lower() != "bearer":
        raise UnauthorizedException(
            code=ErrorCode.UNAUTHORIZED,
            message="Authentication required.",
            details={"reason": "invalid_authorization_scheme"},
        )

    current_user = jwt_verifier.verify(credentials.credentials)

    # Do not trust X-User-ID / X-User-Roles for authorization.
    # Only use verified JWT claims.
    request.state.current_user = current_user

    return current_user


def require_roles(*allowed_roles: str) -> Callable:
    normalized_allowed_roles = {role.upper() for role in allowed_roles}

    async def dependency(
        current_user: CurrentUser = Depends(get_current_user),
    ) -> CurrentUser:
        if not current_user.roles.intersection(normalized_allowed_roles):
            raise ForbiddenException(
                code=ErrorCode.FORBIDDEN,
                message="Insufficient permissions.",
                details={
                    "requiredRoles": sorted(normalized_allowed_roles),
                    "actualRoles": sorted(current_user.roles),
                },
            )

        return current_user

    return dependency


RequireOrganizerOrAdmin = Depends(require_roles("ORGANIZER", "ADMIN"))
RequireAuthenticated = Depends(get_current_user)
