from uuid import UUID, uuid4

from fastapi import UploadFile

from app.schemas.source import StoredSourceItem, StoredSourcesResponse
from app.sources.source_type import SOURCE_TYPE_TO_EXTENSION
from app.sources.source_validator import source_validator
from app.storage.object_storage_client import object_storage_client


class SourceStorageService:
    async def validate_and_store_files(
        self,
        *,
        concert_id: UUID,
        files: list[UploadFile] | None,
    ) -> StoredSourcesResponse:
        validated_sources = await source_validator.validate_files(files)

        stored_items: list[StoredSourceItem] = []
        total_size = 0

        # This is a temporary batch id for source-layer testing.
        # The real job id will be used when the create-job use case is implemented.
        source_batch_id = uuid4()

        for source in validated_sources:
            source_id = uuid4()
            extension = SOURCE_TYPE_TO_EXTENSION[source.source_type]

            object_key = (
                f"ai-bio/{concert_id}/source-batches/{source_batch_id}"
                f"/sources/{source_id}{extension}"
            )

            await object_storage_client.put_bytes(
                object_key=object_key,
                content=source.content,
                content_type=source.content_type,
            )

            total_size += source.file_size_bytes

            stored_items.append(
                StoredSourceItem(
                    sourceId=str(source_id),
                    sourceType=str(source.source_type),
                    originalFileName=source.original_file_name,
                    contentType=source.content_type,
                    fileSizeBytes=source.file_size_bytes,
                    checksumSha256=source.checksum_sha256,
                    objectKey=object_key,
                )
            )

        return StoredSourcesResponse(
            concertId=str(concert_id),
            totalFiles=len(stored_items),
            totalSizeBytes=total_size,
            sources=stored_items,
        )


source_storage_service = SourceStorageService()