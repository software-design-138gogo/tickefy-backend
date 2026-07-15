from pydantic import BaseModel


class ExtractedDocumentItem(BaseModel):
    sourceDocumentId: str
    sourceType: str
    status: str
    extractedCharCount: int
    cleanedCharCount: int
    errorCode: str | None = None


class ExtractionJobResponse(BaseModel):
    jobId: str
    concertId: str
    status: str
    processingStage: str
    totalSources: int
    extractedCount: int
    failedCount: int
    documents: list[ExtractedDocumentItem]