import structlog
from fastapi import FastAPI
from pydantic import BaseModel

structlog.configure(
    processors=[
        structlog.processors.add_log_level,
        structlog.processors.TimeStamper(fmt="iso"),
        structlog.processors.JSONRenderer(),
    ]
)

logger = structlog.get_logger()

app = FastAPI(title="ai-assistant", version="0.1.0")


class HealthResponse(BaseModel):
    status: str


@app.get("/health")
async def health() -> HealthResponse:
    logger.info("health_check")
    return HealthResponse(status="ok")
