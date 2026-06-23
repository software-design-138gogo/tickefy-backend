from app.ai.ai_provider import AiProvider
from app.ai.mock_ai_provider import mock_ai_provider
from app.ai.openai_ai_provider import openai_ai_provider
from app.core.config import get_settings
from app.core.error_codes import ErrorCode
from app.core.exceptions import BadRequestException


class AiProviderFactory:
    def get_provider(self) -> AiProvider:
        settings = get_settings()
        provider = settings.ai_provider.strip().lower()

        if provider == "mock":
            return mock_ai_provider

        if provider == "openai":
            return openai_ai_provider

        raise BadRequestException(
            code=ErrorCode.VALIDATION_ERROR,
            message="AI provider is not supported.",
            details={
                "provider": settings.ai_provider,
                "allowedProviders": ["mock", "openai"],
            },
        )


ai_provider_factory = AiProviderFactory()