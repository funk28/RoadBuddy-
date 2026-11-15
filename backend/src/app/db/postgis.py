import threading
import uuid
from contextlib import contextmanager
from datetime import datetime
from pathlib import Path

from app.services.hazards import Detection, Hazard

_SCHEMA = Path(__file__).with_name("schema.sql").read_text(encoding="utf-8")


def _new_id(prefix: str) -> str:
    return f"{prefix}_{uuid.uuid4().hex[:12]}"


def _hazard_from_row(row: dict) -> Hazard:
    return Hazard(
        id=row["id"],
        hazard_type=row["hazard_type"],
        severity=row["severity"],
        status=row["status"],
        lat=row["lat"],
        lng=row["lng"],
        sighting_count=row["sighting_count"],
        last_confirmed_at=row["last_confirmed_at"],
        expires_at=row["expires_at"],
    )


def _detection_from_row(row: dict) -> Detection:
    return Detection(
        id=row["id"],
        client_detection_id=row["client_detection_id"],
        hazard_id=row["hazard_id"],
        outcome=row["outcome"],
        hazard_type=row["hazard_type"],
        severity=row["severity"],
        lat=row["lat"],
        lng=row["lng"],
        captured_at=row["captured_at"],
        undo_expires_at=row["undo_expires_at"],
    )


class PostgisRepository:
    """PostGIS store. Match candidates come from ST_DWithin; the service applies the same rule."""

    def __init__(self, database_url: str) -> None:
        import psycopg
        from psycopg.rows import dict_row

        self._lock = threading.Lock()
        self._conn = psycopg.connect(database_url, row_factory=dict_row)
        self._apply_schema()

    def _apply_schema(self) -> None:
        with self._conn.cursor() as cur:
            for statement in _SCHEMA.split(";"):
                sql = statement.strip()
                if sql:
                    cur.execute(sql)
        self._conn.commit()

    def close(self) -> None:
        self._conn.close()

    @contextmanager
    def transaction(self, hazard_type: str):
        with self._lock:
            with self._conn.transaction():
                with self._conn.cursor() as cur:
                    cur.execute("SELECT pg_advisory_xact_lock(hashtext(%s))", (hazard_type,))
                yield

    def get_detection_by_client_id(self, client_detection_id: str) -> Detection | None:
        with self._conn.cursor() as cur:
            cur.execute(
                """
                SELECT id, client_detection_id, hazard_id, outcome, hazard_type, severity,
                       ST_Y(geog::geometry) AS lat, ST_X(geog::geometry) AS lng,
                       captured_at, undo_expires_at
                FROM detections
                WHERE client_detection_id = %s
                """,
                (client_detection_id,),
            )
            row = cur.fetchone()
        return None if row is None else _detection_from_row(row)

    def get_hazard(self, hazard_id: str) -> Hazard | None:
        with self._conn.cursor() as cur:
            cur.execute(
                """
                SELECT id, hazard_type, severity, status,
                       ST_Y(geog::geometry) AS lat, ST_X(geog::geometry) AS lng,
                       sighting_count, last_confirmed_at, expires_at
                FROM hazards
                WHERE id = %s
                """,
                (hazard_id,),
            )
            row = cur.fetchone()
        return None if row is None else _hazard_from_row(row)

    def live_hazards(self, hazard_type: str, *, lat: float, lng: float, radius_m: float) -> list[Hazard]:
        # One extra meter keeps a boundary point in the candidate set. find_duplicate applies the real radius.
        with self._conn.cursor() as cur:
            cur.execute(
                """
                SELECT id, hazard_type, severity, status,
                       ST_Y(geog::geometry) AS lat, ST_X(geog::geometry) AS lng,
                       sighting_count, last_confirmed_at, expires_at
                FROM hazards
                WHERE status = 'live'
                  AND hazard_type = %s
                  AND ST_DWithin(
                        geog,
                        ST_SetSRID(ST_MakePoint(%s, %s), 4326)::geography,
                        %s
                  )
                """,
                (hazard_type, lng, lat, radius_m + 1),
            )
            rows = cur.fetchall()
        return [_hazard_from_row(row) for row in rows]

    def list_hazards(self) -> list[Hazard]:
        with self._conn.cursor() as cur:
            cur.execute(
                """
                SELECT id, hazard_type, severity, status,
                       ST_Y(geog::geometry) AS lat, ST_X(geog::geometry) AS lng,
                       sighting_count, last_confirmed_at, expires_at
                FROM hazards
                """
            )
            rows = cur.fetchall()
        return [_hazard_from_row(row) for row in rows]

    def detection_count(self) -> int:
        with self._conn.cursor() as cur:
            cur.execute("SELECT COUNT(*) AS n FROM detections")
            row = cur.fetchone()
        return int(row["n"])

    def set_status(self, hazard_id: str, status: str) -> Hazard:
        with self._conn.cursor() as cur:
            cur.execute(
                """
                UPDATE hazards SET status = %s WHERE id = %s
                RETURNING id, hazard_type, severity, status,
                          ST_Y(geog::geometry) AS lat, ST_X(geog::geometry) AS lng,
                          sighting_count, last_confirmed_at, expires_at
                """,
                (status, hazard_id),
            )
            row = cur.fetchone()
        return _hazard_from_row(row)

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
        hazard_id = _new_id("h")
        detection_id = _new_id("d")
        with self._conn.cursor() as cur:
            cur.execute(
                """
                INSERT INTO hazards (
                    id, hazard_type, severity, status, geog, sighting_count, last_confirmed_at, expires_at
                )
                VALUES (
                    %s, %s, %s, 'live',
                    ST_SetSRID(ST_MakePoint(%s, %s), 4326)::geography,
                    1, %s, %s
                )
                RETURNING id, hazard_type, severity, status,
                          ST_Y(geog::geometry) AS lat, ST_X(geog::geometry) AS lng,
                          sighting_count, last_confirmed_at, expires_at
                """,
                (hazard_id, hazard_type, severity, lng, lat, captured_at, expires_at),
            )
            hazard = _hazard_from_row(cur.fetchone())
            cur.execute(
                """
                INSERT INTO detections (
                    id, client_detection_id, hazard_id, outcome, hazard_type, severity,
                    geog, captured_at, undo_expires_at
                )
                VALUES (
                    %s, %s, %s, 'created', %s, %s,
                    ST_SetSRID(ST_MakePoint(%s, %s), 4326)::geography,
                    %s, %s
                )
                RETURNING id, client_detection_id, hazard_id, outcome, hazard_type, severity,
                          ST_Y(geog::geometry) AS lat, ST_X(geog::geometry) AS lng,
                          captured_at, undo_expires_at
                """,
                (
                    detection_id,
                    client_detection_id,
                    hazard_id,
                    hazard_type,
                    severity,
                    lng,
                    lat,
                    captured_at,
                    undo_expires_at,
                ),
            )
            detection = _detection_from_row(cur.fetchone())
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
        detection_id = _new_id("d")
        with self._conn.cursor() as cur:
            cur.execute(
                """
                UPDATE hazards
                SET sighting_count = sighting_count + 1,
                    last_confirmed_at = %s,
                    expires_at = %s
                WHERE id = %s
                RETURNING id, hazard_type, severity, status,
                          ST_Y(geog::geometry) AS lat, ST_X(geog::geometry) AS lng,
                          sighting_count, last_confirmed_at, expires_at
                """,
                (captured_at, expires_at, hazard_id),
            )
            hazard = _hazard_from_row(cur.fetchone())
            cur.execute(
                """
                INSERT INTO detections (
                    id, client_detection_id, hazard_id, outcome, hazard_type, severity,
                    geog, captured_at, undo_expires_at
                )
                VALUES (
                    %s, %s, %s, 'matched', %s, %s,
                    ST_SetSRID(ST_MakePoint(%s, %s), 4326)::geography,
                    %s, %s
                )
                RETURNING id, client_detection_id, hazard_id, outcome, hazard_type, severity,
                          ST_Y(geog::geometry) AS lat, ST_X(geog::geometry) AS lng,
                          captured_at, undo_expires_at
                """,
                (
                    detection_id,
                    client_detection_id,
                    hazard_id,
                    hazard.hazard_type,
                    hazard.severity,
                    lng,
                    lat,
                    captured_at,
                    undo_expires_at,
                ),
            )
            detection = _detection_from_row(cur.fetchone())
        return hazard, detection
