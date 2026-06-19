from fastapi import FastAPI
from app.api.health import router as health_router
from app.api.ai_bio import router as ai_bio_router
from app.core.config import get_settings
from app.core.request_id import RequestIdMiddleware

settings = get_settings()

app = FastAPI(
    title="Tickefy AI Bio Service",
    description="Generate concert introductions from source documents.",
    version="2.0.0",
    docs_url="/swagger-ui/index.html",
    redoc_url="/redoc",
    openapi_url="/v3/api-docs",
)

app.add_middleware(RequestIdMiddleware)

app.include_router(health_router)
app.include_router(ai_bio_router)


@app.get("/")
async def root():
    return {
        "service": settings.service_name,
        "status": "UP",
    }
