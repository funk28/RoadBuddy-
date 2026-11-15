import threading
from contextlib import contextmanager
from datetime import datetime

from app.services.hazards import Detection, Hazard


class MemoryRepository:
    """Process-local store used in tests and when DATABASE_URL is unset."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._hazards: dict[str, Hazard] = {}
        self._detections: dict[str, Detection] = {}
        self._seq = 0

    @contextmanager
    def transaction(self, hazard_type: str):
        del hazard_type
        with self._lock:
            yield

    def _next_id(self, prefix: str) -> str:
        self._seq += 1
        return f"{prefix}_{self._seq}"

    def get_detection_by_client_id(self, client_detection_id: str) -> Detection | None:
        return self._detections.get(client_detection_id)

    def get_hazard(self, hazard_id: str) -> Hazard | None:
        return self._hazards.get(hazard_id)

    def live_hazards(self, hazard_type: str, *, lat: float, lng: float, radius_m: float) -> list[Hazard]:
        del lat, lng, radius_m
        return [
            hazard
            for hazard in self._hazards.values()
            if hazard.status == "live" and hazard.hazard_type == hazard_type
        ]

    def list_hazards(self) -> list[Hazard]:
        return list(self._hazards.values())

    def detection_count(self) -> int:
        return len(self._detections)

    def set_status(self, hazard_id: str, status: str) -> Hazard:
        current = self._hazards[hazard_id]
        updated = Hazard(
            id=current.id,
            hazard_type=current.hazard_type,
            severity=current.severity,
            status=status,
            lat=current.lat,
            lng=current.lng,
            sighting_count=current.sighting_count,
            last_confirmed_at=current.last_confirmed_at,
            expires_at=current.expires_at,
        )
        self._hazards[hazard_id] = updated
        return updated

    def seed_hazard(
        self,
        *,
        hazard_type: str,
        severity: str,
        status: str,
        lat: float,
        lng: float,
        sighting_count: int,
        last_confirmed_at: datetime,
        expires_at: datetime,
    ) -> Hazard:
        hazard = Hazard(
            id=self._next_id("h"),
            hazard_type=hazard_type,
            severity=severity,
            status=status,
            lat=lat,
            lng=lng,
            sighting_count=sighting_count,
            last_confirmed_at=last_confirmed_at,
            expires_at=expires_at,
        )
        self._hazards[hazard.id] = hazard
        return hazard

    def create_hazard(
        self,
        *,
        client_detection_id: str,
        hazard_type: str,
        severity: str,
        lat: float,
        lng: float,
        captured_at: datetime,
        expires_at: datetime,
        undo_expires_at: datetime,
    ) -> tuple[Hazard, Detection]:
        hazard = Hazard(
            id=self._next_id("h"),
            hazard_type=hazard_type,
            severity=severity,
            status="live",
            lat=lat,
            lng=lng,
            sighting_count=1,
            last_confirmed_at=captured_at,
            expires_at=expires_at,
        )
        detection = Detection(
            id=self._next_id("d"),
            client_detection_id=client_detection_id,
            hazard_id=hazard.id,
            outcome="created",
            hazard_type=hazard_type,
            severity=severity,
            lat=lat,
            lng=lng,
            captured_at=captured_at,
            undo_expires_at=undo_expires_at,
        )
        self._hazards[hazard.id] = hazard
        self._detections[client_detection_id] = detection
        return hazard, detection

    def add_sighting(
        self,
        *,
        hazard_id: str,
        client_detection_id: str,
        lat: float,
        lng: float,
        captured_at: datetime,
        expires_at: datetime,
        undo_expires_at: datetime,
    ) -> tuple[Hazard, Detection]:
        current = self._hazards[hazard_id]
        hazard = Hazard(
            id=current.id,
            hazard_type=current.hazard_type,
            severity=current.severity,
            status=current.status,
            lat=current.lat,
            lng=current.lng,
            sighting_count=current.sighting_count + 1,
            last_confirmed_at=captured_at,
            expires_at=expires_at,
        )
        detection = Detection(
            id=self._next_id("d"),
            client_detection_id=client_detection_id,
            hazard_id=hazard.id,
            outcome="matched",
            hazard_type=current.hazard_type,
            severity=current.severity,
            lat=lat,
            lng=lng,
            captured_at=captured_at,
            undo_expires_at=undo_expires_at,
        )
        self._hazards[hazard.id] = hazard
        self._detections[client_detection_id] = detection
        return hazard, detection
