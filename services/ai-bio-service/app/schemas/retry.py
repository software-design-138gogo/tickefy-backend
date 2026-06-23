from pydantic import BaseModel


class RetryJobResponse(BaseModel):
    jobId: str
    concertId: str
    status: str
    processingStage: str
    retryCount: int
    maxRetries: int
    replayDetected: bool = False