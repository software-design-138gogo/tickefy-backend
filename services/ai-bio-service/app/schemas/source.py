from pydantic import BaseModel


class StoredSourceItem(BaseModel):
    sourceId: str
    sourceType: str
    originalFileName: str | None
    contentType: str | None
    fileSizeBytes: int
    checksumSha256: str
    objectKey: str


class StoredSourcesResponse(BaseModel):
    concertId: str
    totalFiles: int
    totalSizeBytes: int
    sources: list[StoredSourceItem]