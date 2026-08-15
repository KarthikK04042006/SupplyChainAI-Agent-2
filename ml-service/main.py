"""ML Service — demand forecasting, inventory optimization, and analytics.

This service is stateless: the backend passes it data and gets predictions back.
Communication happens over REST (documented at /docs); the backend never talks to
the database, and this service never talks to MySQL.
"""

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from core.config import settings
from routers import explain, forecast, health, inventory

app = FastAPI(
    title=settings.APP_NAME,
    description="Demand forecasting, inventory optimization, and analytics endpoints. "
                "Called by the Spring Boot backend over internal REST.",
    version="0.1.0",
    docs_url="/docs",
    redoc_url="/redoc",
)

# CORS: backend will call us from a different origin in local dev
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],  # tighten in production
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Register routers
app.include_router(health.router)
app.include_router(forecast.router)
app.include_router(inventory.router)
app.include_router(explain.router)


@app.get("/", tags=["root"])
async def root():
    return {
        "service": settings.APP_NAME,
        "status": "running",
        "docs": "/docs",
    }
