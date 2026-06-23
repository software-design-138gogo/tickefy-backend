from pydantic import BaseModel


class GeneratedIntroductionResponse(BaseModel):
    jobId: str
    concertId: str
    status: str
    processingStage: str
    introduction: str
    providerName: str
    providerModel: str
    sourceDocumentIds: list[str]
    sourceTypes: list[str]
    outboxMessageId: str