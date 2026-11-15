"""Duplicate detection for hazard reports.

A new report matches an existing hazard only when all of these hold:

- the hazard type is the same
- the existing hazard status is ``live``
- the distance is within the configured radius (default 25 m)

The nearest live match wins. An equal distance keeps the hazard confirmed
most recently. A match stores another sighting on that hazard, increments
``sighting_count``, and moves expiry forward. Severity is not changed.
"""

from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

from app.services.geo import haversine_m, within_radius

UNDO_WINDOW = timedelta(seconds=5)


@dataclass(frozen=True)
class Hazard:
    id: str
    hazard_type: str
    severity: str
    status: str
    lat: float
    lng: float
    sighting_count: int
    last_confirmed_at: datetime
    expires_at: datetime


@dataclass(frozen=True)
class Detection:
    id: str
    client_detection_id: str
    hazard_id: str | None
    outcome: str
    hazard_type: str | None
    severity: str | None
    lat: float
    lng: float
    captured_at: datetime
    undo_expires_at: datetime | None


@dataclass(frozen=True)
class IncomingReport:
    client_detection_id: str
    captured_at: datetime
    lat: float
    lng: float
    is_hazard: bool
    hazard_type: str | None = None
    severity: str | None = None


@dataclass(frozen=True)
class ReportResult:
    outcome: str
    detection: Detection | None
    hazard: Hazard | None


def find_duplicate(
    candidates: list[Hazard],
    *,
    hazard_type: str,
    lat: float,
    lng: float,
    radius_m: float,
) -> Hazard | None:
    best: Hazard | None = None
    best_key: tuple[float, float, str] | None = None
    for hazard in candidates:
        if hazard.status != "live" or hazard.hazard_type != hazard_type:
            continue
        distance = haversine_m(lat, lng, hazard.lat, hazard.lng)
        if not within_radius(distance, radius_m):
            continue
        confirmed = hazard.last_confirmed_at.astimezone(timezone.utc).timestamp()
        key = (distance, -confirmed, hazard.id)
        if best_key is None or key < best_key:
            best = hazard
            best_key = key
    return best


class HazardService:
    def __init__(self, repository, *, radius_m: float, ttl: timedelta):
        if radius_m < 0:
            raise ValueError("radius_m must be >= 0")
        self._repository = repository
        self._radius_m = radius_m
        self._ttl = ttl

    def report(self, incoming: IncomingReport) -> ReportResult:
        if not incoming.is_hazard:
            return ReportResult(outcome="rejected", detection=None, hazard=None)
        if not incoming.hazard_type or not incoming.severity:
            raise ValueError("a hazard report needs hazard_type and severity")

        with self._repository.transaction(incoming.hazard_type):
            existing = self._repository.get_detection_by_client_id(incoming.client_detection_id)
            if existing is not None:
                hazard = None
                if existing.hazard_id is not None:
                    hazard = self._repository.get_hazard(existing.hazard_id)
                return ReportResult(outcome=existing.outcome, detection=existing, hazard=hazard)

            match = find_duplicate(
                self._repository.live_hazards(
                    incoming.hazard_type,
                    lat=incoming.lat,
                    lng=incoming.lng,
                    radius_m=self._radius_m,
                ),
                hazard_type=incoming.hazard_type,
                lat=incoming.lat,
                lng=incoming.lng,
                radius_m=self._radius_m,
            )
            expires_at = incoming.captured_at + self._ttl
            if match is None:
                hazard, detection = self._repository.create_hazard(
                    client_detection_id=incoming.client_detection_id,
                    hazard_type=incoming.hazard_type,
                    severity=incoming.severity,
                    lat=incoming.lat,
                    lng=incoming.lng,
                    captured_at=incoming.captured_at,
                    expires_at=expires_at,
                    undo_expires_at=incoming.captured_at + UNDO_WINDOW,
                )
                return ReportResult(outcome="created", detection=detection, hazard=hazard)

            hazard, detection = self._repository.add_sighting(
                hazard_id=match.id,
                client_detection_id=incoming.client_detection_id,
                lat=incoming.lat,
                lng=incoming.lng,
                captured_at=incoming.captured_at,
                expires_at=expires_at,
                undo_expires_at=incoming.captured_at + UNDO_WINDOW,
            )
            return ReportResult(outcome="matched", detection=detection, hazard=hazard)
