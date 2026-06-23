import asyncio
import logging

import boto3
from botocore.client import Config
from botocore.exceptions import BotoCoreError, ClientError

from app.core.config import get_settings
from app.core.error_codes import ErrorCode
from app.core.exceptions import DependencyUnavailableException

logger = logging.getLogger(__name__)


class ObjectStorageClient:
    def __init__(self) -> None:
        self.settings = get_settings()
        self._client = boto3.client(
            "s3",
            endpoint_url=self.settings.object_storage_endpoint,
            aws_access_key_id=self.settings.object_storage_access_key,
            aws_secret_access_key=self.settings.object_storage_secret_key,
            region_name=self.settings.object_storage_region,
            config=Config(signature_version="s3v4"),
            use_ssl=self.settings.object_storage_secure,
        )

    async def put_bytes(
        self,
        *,
        object_key: str,
        content: bytes,
        content_type: str | None,
    ) -> None:
        try:
            await asyncio.to_thread(
                self._client.put_object,
                Bucket=self.settings.object_storage_bucket_ai_bio,
                Key=object_key,
                Body=content,
                ContentType=content_type or "application/octet-stream",
            )
        except (BotoCoreError, ClientError) as exc:
            logger.exception("Object storage upload failed")
            raise DependencyUnavailableException(
                code=ErrorCode.OBJECT_STORAGE_UNAVAILABLE,
                message="Object storage is temporarily unavailable.",
                details={"objectKey": object_key},
            ) from exc


object_storage_client = ObjectStorageClient()