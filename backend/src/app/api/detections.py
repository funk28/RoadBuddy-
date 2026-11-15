from datetime import datetime, timedelta, timezone

from flask import Blueprint, current_app, jsonify, request

from app.services.hazards import HazardService, IncomingReport

detections_bp = Blueprint("detections", __name__)


def _iso(value: datetime | None) -> str | None:
    if value is None:
        return None
    text = value.astimezone(timezone.utc).isoformat()
    if text.endswith("+00:00"):
        return text[:-6] + "Z"
    return text


def _error(code: str, message: str, status: int):
    return jsonify({"error": {"code": code, "message": message}}), status


def _read_payload() -> dict | tuple:
    if request.is_json:
        payload = request.get_json(silent=True)
    elif request.form.get("meta"):
        import json

        try:
            payload = json.loads(request.form["meta"])
        except json.JSONDecodeError:
            return _error("invalid_json", "meta is not valid JSON", 400)
    else:
        payload = None
    if not isinstance(payload, dict):
        return _error("invalid_json", "expected a JSON object", 400)
    return payload


def _required_report(payload: dict, classification) -> IncomingReport | tuple:
    client_detection_id = payload.get("client_detection_id")
    location = payload.get("location")
    if not isinstance(client_detection_id, str) or not client_detection_id:
        return _error("missing_field", "client_detection_id is required", 400)
    if not isinstance(location, dict) or "lat" not in location or "lng" not in location:
        return _error("missing_field", "location.lat and location.lng are required", 400)
    try:
        lat = float(location["lat"])
        lng = float(location["lng"])
    except (TypeError, ValueError):
        return _error("invalid_field", "location.lat and location.lng must be numbers", 400)

    captured_raw = payload.get("captured_at")
    if captured_raw is None:
        captured_at = datetime.now(timezone.utc)
    else:
        try:
            captured_at = datetime.fromisoformat(str(captured_raw).replace("Z", "+00:00"))
        except ValueError:
            return _error("invalid_field", "captured_at must be ISO-8601", 400)
        if captured_at.tzinfo is None:
            captured_at = captured_at.replace(tzinfo=timezone.utc)

    return IncomingReport(
        client_detection_id=client_detection_id,
        captured_at=captured_at,
        lat=lat,
        lng=lng,
        is_hazard=classification.is_hazard,
        hazard_type=classification.hazard_type,
        severity=classification.severity,
    )


def _body(result) -> dict:
    detection = result.detection
    hazard = result.hazard
    return {
        "detection_id": None if detection is None else detection.id,
        "outcome": result.outcome,
        "hazard": None
        if hazard is None
        else {
            "id": hazard.id,
            "type": hazard.hazard_type,
            "severity": hazard.severity,
            "status": hazard.status,
            "lat": hazard.lat,
            "lng": hazard.lng,
            "sighting_count": hazard.sighting_count,
            "expires_at": _iso(hazard.expires_at),
        },
        "undo_expires_at": None if detection is None else _iso(detection.undo_expires_at),
    }


@detections_bp.post("/detections")
def create_detection():
    payload = _read_payload()
    if isinstance(payload, tuple):
        return payload

    hint = {
        "hazard_type": payload.get("hazard_type"),
        "severity": payload.get("severity"),
        "confidence": payload.get("local_confidence", 0.92),
    }
    if payload.get("is_hazard") is False or payload.get("label") == "not_hazard":
        hint["is_hazard"] = False
    image = request.files["image"].read() if "image" in request.files else None
    classification = current_app.extensions["hazard_classifier"].classify(image, hint)

    incoming = _required_report(payload, classification)
    if isinstance(incoming, tuple):
        return incoming

    service = HazardService(
        current_app.extensions["hazard_repository"],
        radius_m=float(current_app.config["HAZARD_MATCH_RADIUS_M"]),
        ttl=timedelta(hours=float(current_app.config["HAZARD_TTL_HOURS"])),
    )
    result = service.report(incoming)
    return jsonify(_body(result))
