from dataclasses import dataclass
from uuid import UUID

from app.core.config import get_settings


@dataclass(frozen=True)
class SourceContextItem:
    source_document_id: UUID
    source_type: str
    cleaned_text: str


@dataclass(frozen=True)
class BuiltContext:
    context_text: str
    source_document_ids: list[UUID]
    source_types: list[str]
    total_chars: int


class ContextBuilder:
    def __init__(self) -> None:
        self.settings = get_settings()

    def build(
        self,
        *,
        concert_name: str,
        language: str,
        target_length: str,
        tone: str | None,
        sources: list[SourceContextItem],
    ) -> BuiltContext:
        usable_sources = [
            source
            for source in sources
            if source.cleaned_text and source.cleaned_text.strip()
        ]

        sections: list[str] = [
            f"Concert name: {concert_name}",
            f"Language: {language}",
            f"Target length: {target_length}",
            f"Tone: {tone or 'DEFAULT'}",
            "",
            "Source materials:",
        ]

        source_document_ids: list[UUID] = []
        source_types: list[str] = []
        current_chars = sum(len(section) for section in sections)

        for index, source in enumerate(usable_sources, start=1):
            header = f"\n--- Source {index} ({source.source_type}) ---\n"
            available_chars = self.settings.ai_max_context_chars - current_chars - len(header)

            if available_chars <= 0:
                break

            text = source.cleaned_text.strip()
            text = text[:available_chars]

            sections.append(header)
            sections.append(text)

            current_chars += len(header) + len(text)
            source_document_ids.append(source.source_document_id)
            source_types.append(source.source_type)

        context_text = "\n".join(sections).strip()

        return BuiltContext(
            context_text=context_text,
            source_document_ids=source_document_ids,
            source_types=sorted(set(source_types)),
            total_chars=len(context_text),
        )


context_builder = ContextBuilder()