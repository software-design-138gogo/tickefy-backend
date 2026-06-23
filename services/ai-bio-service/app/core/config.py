from functools import lru_cache
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    service_name: str = "ai-bio-service"
    server_port: int = 8080

    # API
    api_prefix: str = "/api/ai-bio"

    # Database
    db_host: str = "postgres"
    db_port: int = 5432
    db_name: str = "tickefy"
    db_username: str = "tickefy"
    db_password: str = "tickefy"
    db_schema: str = "ai_bio_schema"

    # RabbitMQ
    rabbitmq_host: str = "localhost"
    rabbitmq_port: int = 5672
    rabbitmq_username: str = "tickefy"
    rabbitmq_password: str = "tickefy"
    rabbitmq_exchange: str = "tickefy.exchange"
    rabbitmq_dlx: str = "tickefy.dlx"
    rabbitmq_publish_batch_size: int = 20

    # Event Service
    event_service_url: str = "http://localhost:8082"
    event_service_connect_timeout_seconds: float = 2.0
    event_service_read_timeout_seconds: float = 3.0
    event_service_max_attempts: int = 2

    # Upload limits
    max_source_files: int = 5
    max_file_size_mb: int = 10
    max_total_size_mb: int = 25

    # JWT
    jwt_issuer: str = "tickefy-auth-service"
    jwt_audience: str | None = "tickefy-api"
    jwt_public_key_path: str = "keys/public.pem"
    jwt_algorithm: str = "RS256"
    jwt_leeway_seconds: int = 30
    
    # Object Storage / MinIO
    object_storage_endpoint: str = "http://localhost:9000"
    object_storage_access_key: str = "minioadmin"
    object_storage_secret_key: str = "minioadmin"
    object_storage_bucket_ai_bio: str = "tickefy-ai-bio"
    object_storage_region: str = "us-east-1"
    object_storage_secure: bool = False
    
    # AI generation
    ai_provider: str = "mock"
    ai_model: str = "mock-concert-introduction-v1"
    ai_max_context_chars: int = 12000
    ai_min_output_chars: int = 80
    ai_max_output_chars: int = 1200

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore",
    )

    @property
    def database_url(self) -> str:
        return (
            f"postgresql+psycopg://{self.db_username}:{self.db_password}"
            f"@{self.db_host}:{self.db_port}/{self.db_name}"
        )

    @property
    def rabbitmq_url(self) -> str:
        return (
            f"amqp://{self.rabbitmq_username}:{self.rabbitmq_password}"
            f"@{self.rabbitmq_host}:{self.rabbitmq_port}/"
        )


@lru_cache
def get_settings() -> Settings:
    return Settings()
