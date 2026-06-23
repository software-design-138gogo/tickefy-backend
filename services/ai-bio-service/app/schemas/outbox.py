from pydantic import BaseModel


class PublishedOutboxItem(BaseModel):
    outboxEventId: str
    messageId: str
    eventType: str
    routingKey: str
    status: str


class PublishOutboxResponse(BaseModel):
    publishedCount: int
    failedCount: int
    items: list[PublishedOutboxItem]