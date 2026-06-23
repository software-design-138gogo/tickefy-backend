from enum import StrEnum


class SourceType(StrEnum):
    PDF = "PDF"
    MARKDOWN = "MARKDOWN"
    TEXT = "TEXT"
    DOCX = "DOCX"
    PPTX = "PPTX"
    IMAGE = "IMAGE"
    URL = "URL"


PHASE_1_ALLOWED_SOURCE_TYPES = {
    SourceType.PDF,
    SourceType.MARKDOWN,
    SourceType.TEXT,
    SourceType.DOCX,
    SourceType.PPTX,
}


EXTENSION_TO_SOURCE_TYPE = {
    ".pdf": SourceType.PDF,
    ".md": SourceType.MARKDOWN,
    ".markdown": SourceType.MARKDOWN,
    ".txt": SourceType.TEXT,
    ".docx": SourceType.DOCX,
    ".pptx": SourceType.PPTX,
}


SOURCE_TYPE_TO_EXTENSION = {
    SourceType.PDF: ".pdf",
    SourceType.MARKDOWN: ".md",
    SourceType.TEXT: ".txt",
    SourceType.DOCX: ".docx",
    SourceType.PPTX: ".pptx",
}