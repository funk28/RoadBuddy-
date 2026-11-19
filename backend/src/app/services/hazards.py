"""Merging detections into hazards, and taking a sighting back out."""

from datetime import datetime

from geoalchemy2 import Geography
from geoalchemy2.functions import ST_Distance, ST_DWithin
from sqlalchemy import cast, func, text

from app.extensions import db
from app.models import SEVERITIES, Detection, Hazard, utcnow

# Serializes "find a nearby hazard or create one" so two uploads of the same pothole
# arriving together can't both create a new hazard. Released at commit/rollback.
_MERGE_LOCK_KEY = 42_000_001


def point(lat: float, lng: float):
    """A geography point built from bound parameters (never string-formatted SQL)."""
    return cast(func.ST_SetSRID(func.ST_MakePoint(lng, lat), 4326), Geography(srid=4326))


def more_severe(a: str, b: str) -> str:
    return a if SEVERITIES.index(a) >= SEVERITIES.index(b) else b


def nearest_live_hazard(lat: float, lng: float, radius_m: float) -> Hazard | None:
    here = point(lat, lng)
    return db.session.execute(
        db.select(Hazard)
        .where(Hazard.status == "live", ST_DWithin(Hazard.location, here, radius_m))
        .order_by(ST_Distance(Hazard.location, here))
        .limit(1)
        .with_for_update()
    ).scalar_one_or_none()


def merge_or_create(
    *,
    lat: float,
    lng: float,
    hazard_type: str,
    severity: str,
    seen_at: datetime,
    radius_m: float,
) -> tuple[Hazard, bool]:
    """Returns (hazard, created). Must run inside the caller's transaction."""
    db.session.execute(text("SELECT pg_advisory_xact_lock(:key)"), {"key": _MERGE_LOCK_KEY})

    hazard = nearest_live_hazard(lat, lng, radius_m)
    if hazard is not None:
        hazard.sighting_count += 1
        hazard.last_seen = max(hazard.last_seen, seen_at)
        # A worse rating from a new sighting wins; a milder one doesn't downgrade it.
        hazard.severity = more_severe(hazard.severity, severity)
        return hazard, False

    hazard = Hazard(
        location=point(lat, lng),
        lat=lat,
        lng=lng,
        type=hazard_type,
        severity=severity,
        status="live",
        sighting_count=1,
        first_seen=seen_at,
        last_seen=seen_at,
    )
    db.session.add(hazard)
    db.session.flush()
    return hazard, True


def release_sighting(detection: Detection) -> bool:
    """Marks the detection removed and takes its sighting off the hazard.

    Returns True when that was the hazard's last sighting and the hazard was removed.
    Must run inside the caller's transaction.
    """
    detection.removed_at = utcnow()
    if detection.hazard_id is None:
        return False

    hazard = db.session.execute(
        db.select(Hazard).where(Hazard.id == detection.hazard_id).with_for_update()
    ).scalar_one()
    hazard.sighting_count = max(hazard.sighting_count - 1, 0)
    if hazard.sighting_count == 0:
        hazard.status = "removed"
        return True
    return False
