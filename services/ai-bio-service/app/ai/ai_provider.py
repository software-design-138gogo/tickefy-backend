from dataclasses import dataclass
from typing import Protocol


@dataclass(frozen=True)
class AiGenerationResult:
    introduction: str
    provider_name: str
    provider_model: str


class AiProvider(Protocol):
    @property
    def provider_name(self) -> str:
        ...

    @property
    def provider_model(self) -> str:
        ...

    async def generate_concert_introduction(
        self,
        *,
        concert_name: str,
        context_text: str,
        language: str,
        target_length: str,
        tone: str | None,
    ) -> AiGenerationResult:
        ...