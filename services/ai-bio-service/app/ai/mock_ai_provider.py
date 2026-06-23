import re

from app.ai.ai_provider import AiGenerationResult
from app.core.config import get_settings


class MockAiProvider:
    def __init__(self) -> None:
        self.settings = get_settings()

    @property
    def provider_name(self) -> str:
        return "mock"

    @property
    def provider_model(self) -> str:
        return self.settings.ai_model

    async def generate_concert_introduction(
        self,
        *,
        concert_name: str,
        context_text: str,
        language: str,
        target_length: str,
        tone: str | None,
    ) -> AiGenerationResult:
        compact_context = self._compact_text(context_text)

        if language == "en":
            introduction = self._generate_english(
                concert_name=concert_name,
                compact_context=compact_context,
                tone=tone,
            )
        else:
            introduction = self._generate_vietnamese(
                concert_name=concert_name,
                compact_context=compact_context,
                tone=tone,
            )

        introduction = introduction[: self.settings.ai_max_output_chars].strip()

        return AiGenerationResult(
            introduction=introduction,
            provider_name=self.provider_name,
            provider_model=self.provider_model,
        )

    def _generate_vietnamese(
        self,
        *,
        concert_name: str,
        compact_context: str,
        tone: str | None,
    ) -> str:
        tone_phrase = {
            "ENERGETIC": "sôi động",
            "PROFESSIONAL": "chỉn chu",
            "LUXURY": "cao cấp",
            "FRIENDLY": "gần gũi",
        }.get(tone or "", "cuốn hút")

        source_summary = self._shorten(compact_context, 420)

        return (
            f"{concert_name} là một đêm nhạc {tone_phrase}, được xây dựng từ những chất liệu "
            f"nổi bật trong bộ press kit của chương trình. Sự kiện mang đến không gian biểu diễn "
            f"giàu cảm xúc, kết nối khán giả với câu chuyện âm nhạc, nghệ sĩ và tinh thần sân khấu. "
            f"{source_summary}"
        )

    def _generate_english(
        self,
        *,
        concert_name: str,
        compact_context: str,
        tone: str | None,
    ) -> str:
        tone_phrase = {
            "ENERGETIC": "high-energy",
            "PROFESSIONAL": "well-curated",
            "LUXURY": "premium",
            "FRIENDLY": "warm and accessible",
        }.get(tone or "", "engaging")

        source_summary = self._shorten(compact_context, 420)

        return (
            f"{concert_name} is a {tone_phrase} live music experience shaped by the key materials "
            f"provided in the concert press kit. The event brings together performance, atmosphere, "
            f"artists, and audience energy into a memorable concert story. {source_summary}"
        )

    def _compact_text(self, text: str) -> str:
        text = re.sub(r"\s+", " ", text)
        text = text.replace("Source materials:", "")
        return text.strip()

    def _shorten(self, text: str, max_chars: int) -> str:
        if not text:
            return ""

        if len(text) <= max_chars:
            return text

        return text[:max_chars].rsplit(" ", 1)[0].strip() + "..."


mock_ai_provider = MockAiProvider()