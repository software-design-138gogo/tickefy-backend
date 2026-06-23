from pydantic import BaseModel

from app.schemas.extraction import ExtractionJobResponse
from app.schemas.generation import GeneratedIntroductionResponse
from app.schemas.outbox import PublishOutboxResponse


class PipelineRunResponse(BaseModel):
    jobId: str
    concertId: str
    finalStatus: str
    finalProcessingStage: str
    extraction: ExtractionJobResponse | None
    generation: GeneratedIntroductionResponse | None
    outbox: PublishOutboxResponse | None