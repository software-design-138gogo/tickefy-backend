from fastapi import FastAPI
from contextlib import asynccontextmanager

from app.api.ai_bio import router as ai_bio_router
from app.api.health import router as health_router
from app.core.config import get_settings
from app.core.exception_handlers import register_exception_handlers
from app.core.logging import configure_logging
from app.core.request_id import RequestIdMiddleware
from app.services.background_worker_service import background_worker_service

configure_logging()

settings = get_settings()

@asynccontextmanager
async def lifespan(app: FastAPI):
    background_worker_service.start()

    try:
        yield
    finally:
        await background_worker_service.stop()

app = FastAPI(
    title="Tickefy AI Bio Service",
    description="Generate concert introductions from source documents.",
    lifespan=lifespan,
    version="2.0.0",
    docs_url="/swagger-ui/index.html",
    redoc_url="/redoc",
    openapi_url="/v3/api-docs",
)

app.add_middleware(RequestIdMiddleware)
register_exception_handlers(app)

app.include_router(health_router)
app.include_router(ai_bio_router)


@app.get("/")
async def root():
    return {
        "service": settings.service_name,
        "status": "UP",
    }
