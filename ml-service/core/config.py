"""Application settings, loaded from environment variables."""

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Runtime configuration for the ML service.

    Values come from environment variables prefixed with ``ML_`` (see
    docker-compose.yml), falling back to the defaults below for local runs.
    """

    model_config = SettingsConfigDict(env_prefix="ML_", env_file=".env", extra="ignore")

    APP_NAME: str = "ml-service"
    HOST: str = "0.0.0.0"
    PORT: int = 8000
    LOG_LEVEL: str = "info"


settings = Settings()
