from pydantic import BaseModel


class JobSourceSummary(BaseModel):
    sourceDocumentId: str
    sourceType: str
    status: str
    originalFileName: str | None
    fileSizeBytes: int | None
    warningCount: int
    extractionErrorCode: str | None


class AiBioJobStatusResponse(BaseModel):
    jobId: str
    concertId: str
    concertName: str | None
    organizerId: str | None
    status: str
    processingStage: str
    language: str
    targetLength: str
    tone: str | None
    retryCount: int
    maxRetries: int
    isRetryable: bool
    errorCode: str | None
    errorMessage: str | None
    providerName: str | None
    providerModel: str | None
    generatedIntroduction: str | None
    sourceCount: int
    sources: list[JobSourceSummary]
    requestedAt: str | None
    startedAt: str | None
    completedAt: str | None
    failedAt: str | None
    createdAt: str | None
    updatedAt: str | None


class AiBioJobListItem(BaseModel):
    jobId: str
    concertId: str
    status: str
    processingStage: str
    language: str
    targetLength: str
    tone: str | None
    retryCount: int
    errorCode: str | None
    providerName: str | None
    providerModel: str | None
    requestedAt: str | None
    completedAt: str | None
    failedAt: str | None
    createdAt: str | None


class AiBioJobListResponse(BaseModel):
    concertId: str
    total: int
    items: list[AiBioJobListItem]
