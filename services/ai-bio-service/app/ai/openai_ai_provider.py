import logging

from openai import APIConnectionError, APIError, APIStatusError, APITimeoutError, AsyncOpenAI, RateLimitError

from app.ai.ai_provider import AiGenerationResult
from app.core.config import get_settings
from app.core.error_codes import ErrorCode
from app.core.exceptions import DependencyUnavailableException

logger = logging.getLogger(__name__)


class OpenAiProvider:
    def __init__(self) -> None:
        self.settings = get_settings()
        self._client: AsyncOpenAI | None = None

    @property
    def provider_name(self) -> str:
        return "openai"

    @property
    def provider_model(self) -> str:
        return self.settings.openai_model

    def _get_client(self) -> AsyncOpenAI:
        if not self.settings.openai_api_key:
            raise DependencyUnavailableException(
                code=ErrorCode.AI_PROVIDER_UNAVAILABLE,
                message="AI provider is not configured.",
                details={"provider": self.provider_name},
            )

        if self._client is None:
            self._client = AsyncOpenAI(
                api_key=self.settings.openai_api_key,
                timeout=self.settings.openai_timeout_seconds,
                max_retries=self.settings.openai_max_retries,
            )

        return self._client

    async def generate_concert_introduction(
        self,
        *,
        concert_name: str,
        context_text: str,
        language: str,
        target_length: str,
        tone: str | None,
    ) -> AiGenerationResult:
        client = self._get_client()

        instructions = self._build_instructions(
            language=language,
            target_length=target_length,
            tone=tone,
        )

        input_text = self._build_input(
            concert_name=concert_name,
            context_text=context_text,
        )

        try:
            response = await client.responses.create(
                model=self.settings.openai_model,
                instructions=instructions,
                input=input_text,
                max_output_tokens=self.settings.openai_max_output_tokens,
            )
        except RateLimitError as exc:
            raise DependencyUnavailableException(
                code=ErrorCode.AI_PROVIDER_UNAVAILABLE,
                message="AI provider rate limit exceeded.",
                details={"provider": self.provider_name},
            ) from exc
        except (APITimeoutError, APIConnectionError) as exc:
            raise DependencyUnavailableException(
                code=ErrorCode.AI_PROVIDER_UNAVAILABLE,
                message="AI provider is temporarily unavailable.",
                details={
                    "provider": self.provider_name,
                    "reason": type(exc).__name__,
                },
            ) from exc
        except APIStatusError as exc:
            raise DependencyUnavailableException(
                code=ErrorCode.AI_PROVIDER_UNAVAILABLE,
                message="AI provider returned an error response.",
                details={
                    "provider": self.provider_name,
                    "statusCode": exc.status_code,
                },
            ) from exc
        except APIError as exc:
            raise DependencyUnavailableException(
                code=ErrorCode.AI_PROVIDER_UNAVAILABLE,
                message="AI provider request failed.",
                details={
                    "provider": self.provider_name,
                    "reason": type(exc).__name__,
                },
            ) from exc

        introduction = (response.output_text or "").strip()

        if not introduction:
            raise DependencyUnavailableException(
                code=ErrorCode.AI_PROVIDER_INVALID_RESPONSE,
                message="AI provider returned an empty response.",
                details={"provider": self.provider_name},
            )

        return AiGenerationResult(
            introduction=introduction,
            provider_name=self.provider_name,
            provider_model=self.provider_model,
        )

    def _build_instructions(
        self,
        *,
        language: str,
        target_length: str,
        tone: str | None,
    ) -> str:
        output_language = "Vietnamese" if language == "vi" else "English"
        tone_text = tone or "natural and engaging"

        return (
            "You generate public concert introductions for a ticketing platform. "
            "Use only the provided source context. "
            "Do not invent dates, venues, prices, ticket policies, sponsors, or artist names. "
            "Do not mention that the content was generated from source documents. "
            "Return only the final introduction text, without markdown, bullets, JSON, or explanations. "
            f"Output language: {output_language}. "
            f"Target length: {target_length}. "
            f"Tone: {tone_text}."
        )

    def _build_input(
        self,
        *,
        concert_name: str,
        context_text: str,
    ) -> str:
        return (
            f"Concert name:\n{concert_name}\n\n"
            "Source context:\n"
            f"{context_text}\n\n"
            "Write the final concert introduction."
        )


openai_ai_provider = OpenAiProvider()