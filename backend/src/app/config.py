import os


def _bool(name: str, default: bool = False) -> bool:
    return os.environ.get(name, str(default)).strip().lower() in {"1", "true", "yes"}


class Config:
    """Settings read from the environment. Every variable is listed in .env.example."""

    SQLALCHEMY_DATABASE_URI = os.environ.get(
        "DATABASE_URL", "postgresql+psycopg://roadbuddy:roadbuddy@localhost:5433/roadbuddy"
    )
    SQLALCHEMY_ENGINE_OPTIONS = {"pool_pre_ping": True}

    GOOGLE_CLIENT_IDS = [
        cid.strip() for cid in os.environ.get("GOOGLE_CLIENT_IDS", "").split(",") if cid.strip()
    ]
    DEV_AUTH = _bool("DEV_AUTH")

    IMAGE_STORAGE_DIR = os.environ.get("IMAGE_STORAGE_DIR", "./var/images")
    # Frames are a single JPEG; anything bigger than this is rejected with 413.
    MAX_CONTENT_LENGTH = 10 * 1024 * 1024

    VLM_CLASSIFIER = os.environ.get("VLM_CLASSIFIER", "stub")
    VLM_TIMEOUT_S = float(os.environ.get("VLM_TIMEOUT_S", "5"))

    HAZARD_MATCH_RADIUS_M = float(os.environ.get("HAZARD_MATCH_RADIUS_M", "15"))
    UNDO_WINDOW_S = 5
