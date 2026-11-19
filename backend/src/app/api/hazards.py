"""Contract calls 4 (GET /v1/hazards) and 5 (PATCH /v1/hazards/{id})."""

from typing import Any

from flask import jsonify, request
from geoalchemy2.functions import ST_Distance, ST_DWithin
from sqlalchemy import exists

from app.api import v1
from app.api import validation as v
from app.auth import current_user
from app.errors import bad_request, not_found
from app.extensions import db
from app.models import HAZARD_STATUSES, SEVERITIES, Detection, Hazard
from app.serialize import HAZARD_PREFIX, hazard_public_id, parse_id
from app.services.hazards import point

# Keeps one request from scanning the whole table; the Hazards screen asks for ~2 km.
MAX_RADIUS_M = 50_000
MAX_RESULTS = 500
PATCHABLE_FIELDS = {"severity", "status"}


def _query_float(name: str, minimum: float, maximum: float) -> float:
    raw = request.args.get(name)
    if raw is None:
        raise bad_request(f"{name} is required.")
    try:
        value = float(raw)
    except ValueError as exc:
        raise bad_request(f"{name} must be a number.") from exc
    return v.number(value, name, minimum=minimum, maximum=maximum)


def _query_radius() -> int:
    raw = request.args.get("radius_m")
    if raw is None:
        raise bad_request("radius_m is required.")
    if not raw.isdigit():
        raise bad_request("radius_m must be a whole number of meters.")
    radius = int(raw)
    if not 1 <= radius <= MAX_RADIUS_M:
        raise bad_request(f"radius_m must be between 1 and {MAX_RADIUS_M}.")
    return radius


def _query_bool(name: str) -> bool:
    raw = request.args.get(name)
    if raw is None:
        return False
    if raw.lower() in {"true", "1"}:
        return True
    if raw.lower() in {"false", "0"}:
        return False
    raise bad_request(f"{name} must be true or false.")


def _enum(value: Any, field: str, allowed: tuple[str, ...]) -> str:
    if value not in allowed:
        raise bad_request(f"{field} must be one of: {', '.join(allowed)}.")
    return value


@v1.get("/hazards")
def list_hazards():
    lat = _query_float("lat", -90, 90)
    lng = _query_float("lng", -180, 180)
    radius_m = _query_radius()
    severity = request.args.get("severity")
    if severity is not None:
        _enum(severity, "severity", SEVERITIES)
    mine_only = _query_bool("mine")

    here = point(lat, lng)
    distance = ST_Distance(Hazard.location, here).label("distance_m")
    reported_by_me = (
        exists()
        .where(
            Detection.hazard_id == Hazard.id,
            Detection.user_id == current_user().id,
            Detection.removed_at.is_(None),
        )
        .label("reported_by_me")
    )
    query = (
        db.select(Hazard, distance, reported_by_me)
        .where(Hazard.status == "live", ST_DWithin(Hazard.location, here, radius_m))
        .order_by(distance)
        .limit(MAX_RESULTS)
    )
    if severity is not None:
        query = query.where(Hazard.severity == severity)
    if mine_only:
        query = query.where(reported_by_me)

    hazards = [
        {
            "id": hazard_public_id(hazard.id),
            "type": hazard.type,
            "severity": hazard.severity,
            "lat": hazard.lat,
            "lng": hazard.lng,
            "distance_m": round(float(dist), 1),
            "sighting_count": hazard.sighting_count,
            "reported_by_me": bool(mine),
        }
        for hazard, dist, mine in db.session.execute(query)
    ]
    return jsonify({"hazards": hazards})


@v1.patch("/hazards/<hazard_id>")
def update_hazard(hazard_id: str):
    pk = parse_id(hazard_id, HAZARD_PREFIX)
    body = request.get_json(silent=True)
    if not isinstance(body, dict) or not body:
        raise bad_request("Send a JSON object with severity and/or status.")
    unknown = set(body) - PATCHABLE_FIELDS
    if unknown:
        raise bad_request(f"Unknown field(s): {', '.join(sorted(unknown))}.")

    hazard = db.session.execute(
        db.select(Hazard).where(Hazard.id == pk).with_for_update()
    ).scalar_one_or_none()
    if hazard is None:
        raise not_found("No hazard with that id.")

    if "severity" in body:
        hazard.severity = _enum(body["severity"], "severity", SEVERITIES)
    if "status" in body:
        hazard.status = _enum(body["status"], "status", HAZARD_STATUSES)
    db.session.commit()
    return jsonify(
        {"id": hazard_public_id(hazard.id), "severity": hazard.severity, "status": hazard.status}
    )
