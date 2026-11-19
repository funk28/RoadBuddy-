"""Shared helpers for turning rows into the contract's JSON shapes."""

from datetime import UTC, datetime

from app.errors import not_found
from app.models import Hazard

DETECTION_PREFIX = "d_"
HAZARD_PREFIX = "h_"


def iso(value: datetime) -> str:
    """ISO-8601 UTC with a Z suffix, e.g. 2026-11-13T14:48:02Z."""
    return value.astimezone(UTC).isoformat(timespec="seconds").replace("+00:00", "Z")


def detection_public_id(pk: int) -> str:
    return f"{DETECTION_PREFIX}{pk}"


def hazard_public_id(pk: int) -> str:
    return f"{HAZARD_PREFIX}{pk}"


def parse_id(public_id: str, prefix: str) -> int:
    """`d_123` -> 123. Anything malformed is a 404, the same as an unknown id."""
    digits = public_id.removeprefix(prefix)
    if not public_id.startswith(prefix) or not digits.isdigit() or len(digits) > 18:
        raise not_found()
    return int(digits)


def hazard_summary(hazard: Hazard) -> dict:
    return {
        "id": hazard_public_id(hazard.id),
        "severity": hazard.severity,
        "lat": hazard.lat,
        "lng": hazard.lng,
        "sighting_count": hazard.sighting_count,
    }
