import hashlib
import zipfile
from dataclasses import dataclass
from io import BytesIO
from pathlib import Path

from fastapi import UploadFile

from app.core.config import get_settings
from app.core.exceptions import (
    invalid_source_type,
    source_required,
    source_too_large,
    unsupported_source_type,
)
from app.sources.source_type import (
    EXTENSION_TO_SOURCE_TYPE,
    PHASE_1_ALLOWED_SOURCE_TYPES,
    SourceType,
)


@dataclass(frozen=True)
class ValidatedSource:
    source_type: SourceType
    original_file_name: str | None
    content_type: str | None
    file_size_bytes: int
    checksum_sha256: str
    content: bytes


class SourceValidator:
    def __init__(self) -> None:
        self.settings = get_settings()

    async def validate_files(self, files: list[UploadFile] | None) -> list[ValidatedSource]:
        if not files:
            raise source_required()

        if len(files) > self.settings.max_source_files:
            raise source_too_large(f"Max {self.settings.max_source_files} files per job")

        validated_sources: list[ValidatedSource] = []
        total_size = 0

        for file in files:
            content = await file.read()
            file_size = len(content)
            total_size += file_size

            max_file_size_bytes = self.settings.max_file_size_mb * 1024 * 1024
            max_total_size_bytes = self.settings.max_total_size_mb * 1024 * 1024

            if file_size <= 0:
                raise invalid_source_type("Empty file is not allowed")

            if file_size > max_file_size_bytes:
                raise source_too_large(f"Max {self.settings.max_file_size_mb} MB per file")

            if total_size > max_total_size_bytes:
                raise source_too_large(f"Max {self.settings.max_total_size_mb} MB total per job")

            source_type = self._detect_source_type(
                filename=file.filename,
                content_type=file.content_type,
            )

            if source_type not in PHASE_1_ALLOWED_SOURCE_TYPES:
                raise unsupported_source_type(str(source_type))

            self._validate_magic_bytes(
                source_type=source_type,
                content=content,
            )

            validated_sources.append(
                ValidatedSource(
                    source_type=source_type,
                    original_file_name=file.filename,
                    content_type=file.content_type,
                    file_size_bytes=file_size,
                    checksum_sha256=hashlib.sha256(content).hexdigest(),
                    content=content,
                )
            )

        return validated_sources

    def _detect_source_type(
        self,
        *,
        filename: str | None,
        content_type: str | None,
    ) -> SourceType:
        extension = Path(filename or "").suffix.lower()

        if extension not in EXTENSION_TO_SOURCE_TYPE:
            raise unsupported_source_type(extension or content_type or "unknown")

        return EXTENSION_TO_SOURCE_TYPE[extension]

    def _validate_magic_bytes(
        self,
        *,
        source_type: SourceType,
        content: bytes,
    ) -> None:
        if source_type == SourceType.PDF:
            if not content.startswith(b"%PDF-"):
                raise invalid_source_type("PDF magic bytes do not match")
            return

        if source_type in {SourceType.MARKDOWN, SourceType.TEXT}:
            self._validate_utf8_text(content)
            return

        if source_type == SourceType.DOCX:
            self._validate_office_zip(
                content=content,
                required_prefix="word/",
                source_name="DOCX",
            )
            return

        if source_type == SourceType.PPTX:
            self._validate_office_zip(
                content=content,
                required_prefix="ppt/",
                source_name="PPTX",
            )
            return

        raise unsupported_source_type(str(source_type))

    def _validate_utf8_text(self, content: bytes) -> None:
        try:
            content.decode("utf-8")
        except UnicodeDecodeError as exc:
            raise invalid_source_type("Text source must be valid UTF-8") from exc

    def _validate_office_zip(
        self,
        *,
        content: bytes,
        required_prefix: str,
        source_name: str,
    ) -> None:
        if not content.startswith(b"PK"):
            raise invalid_source_type(f"{source_name} must be a valid Office Open XML zip file")

        try:
            with zipfile.ZipFile(BytesIO(content)) as archive:
                names = archive.namelist()
        except zipfile.BadZipFile as exc:
            raise invalid_source_type(f"{source_name} zip structure is invalid") from exc

        if not any(name.startswith(required_prefix) for name in names):
            raise invalid_source_type(f"{source_name} internal structure is invalid")


source_validator = SourceValidator()