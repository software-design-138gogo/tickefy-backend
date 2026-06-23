from pathlib import Path
from uuid import UUID

import jwt
from jwt import ExpiredSignatureError, InvalidAudienceError, InvalidIssuerError, InvalidTokenError

from app.core.config import get_settings
from app.core.error_codes import ErrorCode
from app.core.exceptions import UnauthorizedException
from app.security.principal import CurrentUser


class JwtVerifier:
    def __init__(self) -> None:
        self.settings = get_settings()
        self._public_key: str | None = None

    def _load_public_key(self) -> str:
        if self._public_key is not None:
            return self._public_key

        public_key_path = Path(self.settings.jwt_public_key_path)

        if not public_key_path.exists():
            raise UnauthorizedException(
                code=ErrorCode.INVALID_TOKEN,
                message="Unable to verify the login session.",
                details={"reason": "JWT public key is not configured"},
            )

        self._public_key = public_key_path.read_text(encoding="utf-8")
        return self._public_key

    def verify(self, token: str) -> CurrentUser:
        public_key = self._load_public_key()

        options = {
            "require": ["exp", "sub"],
            "verify_signature": True,
            "verify_exp": True,
            "verify_iss": bool(self.settings.jwt_issuer),
            "verify_aud": bool(self.settings.jwt_audience),
        }

        decode_kwargs = {
            "jwt": token,
            "key": public_key,
            "algorithms": [self.settings.jwt_algorithm],
            "options": options,
            "leeway": self.settings.jwt_leeway_seconds,
        }

        if self.settings.jwt_issuer:
            decode_kwargs["issuer"] = self.settings.jwt_issuer

        if self.settings.jwt_audience:
            decode_kwargs["audience"] = self.settings.jwt_audience

        try:
            claims = jwt.decode(**decode_kwargs)
        except ExpiredSignatureError as exc:
            raise UnauthorizedException(
                code=ErrorCode.INVALID_TOKEN,
                message="Invalid session.",
                details={"reason": "token_expired"},
            ) from exc
        except InvalidIssuerError as exc:
            raise UnauthorizedException(
                code=ErrorCode.INVALID_TOKEN,
                message="Invalid session.",
                details={"reason": "invalid_issuer"},
            ) from exc
        except InvalidAudienceError as exc:
            raise UnauthorizedException(
                code=ErrorCode.INVALID_TOKEN,
                message="Invalid session.",
                details={"reason": "invalid_audience"},
            ) from exc
        except InvalidTokenError as exc:
            raise UnauthorizedException(
                code=ErrorCode.INVALID_TOKEN,
                message="Invalid session.",
                details={"reason": "invalid_token"},
            ) from exc

        subject = claims.get("sub")
        if not subject:
            raise UnauthorizedException(
                code=ErrorCode.INVALID_TOKEN,
                message="Invalid session.",
                details={"reason": "missing_sub"},
            )

        try:
            user_id = UUID(str(subject))
        except ValueError as exc:
            raise UnauthorizedException(
                code=ErrorCode.INVALID_TOKEN,
                message="Invalid session.",
                details={"reason": "invalid_sub"},
            ) from exc

        raw_roles = claims.get("roles") or []
        if not isinstance(raw_roles, list):
            raise UnauthorizedException(
                code=ErrorCode.INVALID_TOKEN,
                message="Invalid session.",
                details={"reason": "invalid_roles"},
            )

        roles = {str(role).strip().upper() for role in raw_roles if str(role).strip()}

        return CurrentUser(
            user_id=user_id,
            email=claims.get("email"),
            roles=roles,
            token=token,
            claims=claims,
        )


jwt_verifier = JwtVerifier()
