from datetime import datetime, timezone
from typing import Any
from uuid import UUID

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core.config import get_settings
from app.db.models import OutboxEvent
from app.messaging.rabbitmq_client import rabbitmq_client
from app.schemas.outbox import PublishedOutboxItem, PublishOutboxResponse


class OutboxPublisherService:
    def __init__(self) -> None:
        self.settings = get_settings()

    async def publish_pending_events(
        self,
        *,
        db: Session,
        limit: int | None = None,
        aggregate_id: UUID | None = None,
    ) -> PublishOutboxResponse:
        batch_limit = limit or self.settings.rabbitmq_publish_batch_size

        events = self._load_pending_events(
            db=db,
            limit=batch_limit,
            aggregate_id=aggregate_id,
        )

        published_items: list[PublishedOutboxItem] = []
        failed_count = 0

        for event in events:
            try:
                envelope = self._build_envelope(event)

                await rabbitmq_client.publish_event(
                    exchange_name=event.exchange_name,
                    routing_key=event.routing_key,
                    envelope=envelope,
                )

                now = self._utc_now()
                event.status = "PUBLISHED"
                event.published_at = now
                event.updated_at = now
                event.last_error = None

                db.commit()

                published_items.append(
                    PublishedOutboxItem(
                        outboxEventId=str(event.id),
                        messageId=str(event.message_id),
                        eventType=event.event_type,
                        routingKey=event.routing_key,
                        status=event.status,
                    )
                )

            except Exception as exc:
                failed_count += 1

                now = self._utc_now()
                event.retry_count += 1
                event.last_error = type(exc).__name__
                event.updated_at = now

                if event.retry_count >= 5:
                    event.status = "FAILED"

                db.commit()

        return PublishOutboxResponse(
            publishedCount=len(published_items),
            failedCount=failed_count,
            items=published_items,
        )

    def _load_pending_events(
        self,
        *,
        db: Session,
        limit: int,
        aggregate_id: UUID | None = None,
    ) -> list[OutboxEvent]:
        conditions = [
            OutboxEvent.status == "PENDING",
            OutboxEvent.available_at <= self._utc_now(),
        ]

        if aggregate_id is not None:
            conditions.append(OutboxEvent.aggregate_id == aggregate_id)

        statement = (
            select(OutboxEvent)
            .where(*conditions)
            .order_by(OutboxEvent.created_at.asc())
            .limit(limit)
        )

        return list(db.execute(statement).scalars().all())

    def _build_envelope(
        self,
        event: OutboxEvent,
    ) -> dict[str, Any]:
        return {
            "messageId": str(event.message_id),
            "eventType": event.event_type,
            "eventVersion": event.event_version,
            "source": event.source_service,
            "occurredAt": self._to_iso(event.created_at),
            "correlationId": event.correlation_id,
            "causationId": str(event.causation_id) if event.causation_id else None,
            "payload": event.payload,
        }

    def _utc_now(self) -> datetime:
        return datetime.now(timezone.utc)

    def _to_iso(self, value: datetime) -> str:
        return value.isoformat().replace("+00:00", "Z")


outbox_publisher_service = OutboxPublisherService()