import json
import logging
from typing import Any

import aio_pika
from aio_pika import DeliveryMode, ExchangeType, Message

from app.core.config import get_settings
from app.core.error_codes import ErrorCode
from app.core.exceptions import DependencyUnavailableException

logger = logging.getLogger(__name__)


class RabbitMqClient:
    def __init__(self) -> None:
        self.settings = get_settings()

    async def publish_event(
        self,
        *,
        exchange_name: str,
        routing_key: str,
        envelope: dict[str, Any],
    ) -> None:
        connection = None

        try:
            connection = await aio_pika.connect_robust(
                host=self.settings.rabbitmq_host,
                port=self.settings.rabbitmq_port,
                login=self.settings.rabbitmq_username,
                password=self.settings.rabbitmq_password,
            )

            channel = await connection.channel(publisher_confirms=True)

            exchange = await channel.declare_exchange(
                exchange_name,
                ExchangeType.TOPIC,
                durable=True,
                auto_delete=False,
            )

            body = json.dumps(
                envelope,
                ensure_ascii=False,
                separators=(",", ":"),
            ).encode("utf-8")

            message = Message(
                body=body,
                content_type="application/json",
                delivery_mode=DeliveryMode.PERSISTENT,
                message_id=str(envelope["messageId"]),
                correlation_id=str(envelope["correlationId"]),
                type=str(envelope["eventType"]),
                headers={
                    "eventType": str(envelope["eventType"]),
                    "eventVersion": str(envelope["eventVersion"]),
                    "source": str(envelope["source"]),
                },
            )

            await exchange.publish(
                message,
                routing_key=routing_key,
                mandatory=False,
            )

        except Exception as exc:
            logger.exception("RabbitMQ publish failed")
            raise DependencyUnavailableException(
                code=ErrorCode.SERVICE_UNAVAILABLE,
                message="RabbitMQ is temporarily unavailable.",
                details={
                    "exchange": exchange_name,
                    "routingKey": routing_key,
                    "reason": type(exc).__name__,
                },
            ) from exc

        finally:
            if connection is not None:
                await connection.close()


rabbitmq_client = RabbitMqClient()