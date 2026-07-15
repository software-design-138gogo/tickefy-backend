from datetime import datetime
from uuid import UUID

from pydantic import BaseModel, Field


class AiConcertContextResponse(BaseModel):
    concert_id: UUID = Field(alias="concertId")
    concert_name: str = Field(alias="concertName")
    organizer_id: UUID = Field(alias="organizerId")
    status: str
    current_introduction_updated_at: datetime | None = Field(
        default=None,
        alias="currentIntroductionUpdatedAt",
    )
    manual_introduction_updated_at: datetime | None = Field(
        default=None,
        alias="manualIntroductionUpdatedAt",
    )

    model_config = {
        "populate_by_name": True,
    }