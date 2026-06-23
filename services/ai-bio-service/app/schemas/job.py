from pydantic import BaseModel


class CreateAiBioJobResponse(BaseModel):
    jobId: str
    concertId: str
    status: str
    processingStage: str
    replayDetected: bool
    sourceCount: int
    language: str
    targetLength: str
    tone: str | None
    createdAt: str