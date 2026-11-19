from datetime import UTC, datetime

from geoalchemy2 import Geography
from sqlalchemy import (
    Boolean,
    CheckConstraint,
    DateTime,
    Float,
    ForeignKey,
    Integer,
    String,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.extensions import db

SEVERITIES = ("minor", "moderate", "severe")
HAZARD_STATUSES = ("live", "expired", "removed")
OUTCOMES = ("created", "matched", "rejected")


def utcnow() -> datetime:
    return datetime.now(UTC)


def _in(column: str, values: tuple[str, ...]) -> str:
    return f"{column} IN ({', '.join(repr(v) for v in values)})"


class User(db.Model):  # type: ignore[name-defined]
    __tablename__ = "users"

    id: Mapped[int] = mapped_column(primary_key=True)
    google_sub: Mapped[str] = mapped_column(String(255), unique=True)
    email: Mapped[str | None] = mapped_column(String(320))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)


class Hazard(db.Model):  # type: ignore[name-defined]
    __tablename__ = "hazards"
    __table_args__ = (
        CheckConstraint(_in("severity", SEVERITIES), name="ck_hazards_severity"),
        CheckConstraint(_in("status", HAZARD_STATUSES), name="ck_hazards_status"),
        CheckConstraint("sighting_count >= 0", name="ck_hazards_sighting_count"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    # geography(Point, 4326) with a GiST index (GeoAlchemy2 adds the index).
    location = mapped_column(Geography("POINT", srid=4326, spatial_index=True), nullable=False)
    type: Mapped[str] = mapped_column(String(32))
    severity: Mapped[str] = mapped_column(String(16))
    status: Mapped[str] = mapped_column(String(16), default="live")
    sighting_count: Mapped[int] = mapped_column(Integer, default=1)
    first_seen: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    last_seen: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    # Kept in sync with `location` so responses don't need a PostGIS round trip.
    lat: Mapped[float] = mapped_column(Float)
    lng: Mapped[float] = mapped_column(Float)


class Detection(db.Model):  # type: ignore[name-defined]
    __tablename__ = "detections"
    __table_args__ = (CheckConstraint(_in("outcome", OUTCOMES), name="ck_detections_outcome"),)

    id: Mapped[int] = mapped_column(primary_key=True)
    # Made on the phone; UNIQUE so a retried upload can't create a second detection.
    client_detection_id: Mapped[str] = mapped_column(String(128), unique=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    hazard_id: Mapped[int | None] = mapped_column(ForeignKey("hazards.id"), index=True)
    outcome: Mapped[str] = mapped_column(String(16))
    captured_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    received_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    lat: Mapped[float] = mapped_column(Float)
    lng: Mapped[float] = mapped_column(Float)
    heading_deg: Mapped[float | None] = mapped_column(Float)
    local_confidence: Mapped[float | None] = mapped_column(Float)
    # Optional, set by the phone per drive; lets My reports filter to "This trip".
    trip_id: Mapped[str | None] = mapped_column(String(64))
    image_key: Mapped[str] = mapped_column(String(255))
    # What the classifier said about this frame.
    is_hazard: Mapped[bool] = mapped_column(Boolean)
    hazard_type: Mapped[str | None] = mapped_column(String(32))
    severity: Mapped[str | None] = mapped_column(String(16))
    classifier_confidence: Mapped[float] = mapped_column(Float)
    model_id: Mapped[str] = mapped_column(String(64))
    # Set when the detection is undone or deleted; it then stops counting as a sighting.
    removed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))

    hazard: Mapped[Hazard | None] = relationship()
    user: Mapped[User] = relationship()


class UserSettings(db.Model):  # type: ignore[name-defined]
    __tablename__ = "user_settings"

    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), primary_key=True)
    audible_alerts: Mapped[bool] = mapped_column(Boolean)
    detection_enabled: Mapped[bool] = mapped_column(Boolean)
    suggest_reroutes: Mapped[bool] = mapped_column(Boolean)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
