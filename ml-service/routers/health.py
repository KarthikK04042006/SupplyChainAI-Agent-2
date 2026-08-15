"""Health check endpoint, polled by Docker and by the backend's /api/health."""

from fastapi import APIRouter

from core.config import settings
from models.schemas import HealthResponse

router = APIRouter(tags=["health"])


@router.get("/health", response_model=HealthResponse, summary="Service health")
async def health() -> HealthResponse:
    return HealthResponse(status="UP", service=settings.APP_NAME)
