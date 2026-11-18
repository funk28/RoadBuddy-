"""Contract calls 1 (POST /v1/detections) and 2 (DELETE /v1/detections/{id})."""

import json
from dataclasses import dataclass
from datetime import datetime, timedelta

from flask import current_app, jsonify, request
from sqlalchemy.exc import IntegrityError

from app.api import v1
from app.api import validation as v
from app.auth import current_user
from app.errors import ApiError, bad_request, not_found
from app.extensions import db
from app.models import Detection, utcnow
from app.serialize import (
    DETECTION_PREFIX,
    detection_public_id,
    hazard_summary,
    iso,
    parse_id,
)
from app.services import hazards
from app.services.storage import ImageStore, is_jpeg
from app.services.vlm_client import (
    ClassifierUnavailable,
    ClassifyHint,
    classify_with_timeout,
)

MAX_CLIENT_ID_LENGTH = 128


@dataclass(frozen=True)
class DetectionMeta:
    client_detection_id: str
    captured_at: datetime
    lat: float
    lng: float
    heading_deg: float | None
    local_confidence: float | None


def _parse_meta(raw: str | None) -> DetectionMeta:
    if raw is None:
        raise bad_request("The meta part is required.")
    try:
        meta = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise bad_request("meta must be valid JSON.") from exc
    if not isinstance(meta, dict):
        raise bad_request("meta must be a JSON object.")

    client_id = meta.get("client_detection_id")
    if not isinstance(client_id, str) or not client_id.strip():
        raise bad_request("meta.client_detection_id is required.")
    if len(client_id) > MAX_CLIENT_ID_LENGTH:
        raise bad_request(f"meta.client_detection_id is longer than {MAX_CLIENT_ID_LENGTH}.")
    if "captured_at" not in meta:
        raise bad_request("meta.captured_at is required.")
    if "location" not in meta:
        raise bad_request("meta.location is required.")

    lat, lng = v.location(meta["location"], "meta.location")
    heading = meta.get("heading_deg")
    confidence = meta.get("local_confidence")
    return DetectionMeta(
        client_detection_id=client_id,
        captured_at=v.timestamp(meta["captured_at"], "meta.captured_at"),
        lat=lat,
        lng=lng,
        heading_deg=None
        if heading is None
        else v.number(heading, "meta.heading_deg", minimum=0, exclusive_maximum=360),
        local_confidence=None
        if confidence is None
        else v.number(confidence, "meta.local_confidence", minimum=0, maximum=1),
    )


def _read_image() -> bytes:
    upload = request.files.get("image")
    if upload is None:
        raise bad_request("The image part is required.")
    data = upload.read()
    if not is_jpeg(data):
        raise bad_request("image must be a JPEG.")
    return data


def _undo_expires_at(detection: Detection) -> datetime:
    return detection.received_at + timedelta(seconds=current_app.config["UNDO_WINDOW_S"])


def _result(detection: Detection) -> dict:
    body: dict = {
        "detection_id": detection_public_id(detection.id),
        "outcome": detection.outcome,
    }
    if detection.hazard is not None:
        body["hazard"] = hazard_summary(detection.hazard)
        body["undo_expires_at"] = iso(_undo_expires_at(detection))
    return body


def _existing(client_detection_id: str) -> Detection | None:
    existing = db.session.execute(
        db.select(Detection).filter_by(client_detection_id=client_detection_id)
    ).scalar_one_or_none()
    if existing is not None and existing.user_id != current_user().id:
        raise bad_request(
            "client_detection_id is already used.", code="client_detection_id_conflict"
        )
    return existing


@v1.post("/detections")
def report_detection():
    meta = _parse_meta(request.form.get("meta"))
    image = _read_image()

    # A retry of an upload we already processed gets the original result back,
    # without calling the classifier again.
    existing = _existing(meta.client_detection_id)
    if existing is not None:
        return jsonify(_result(existing))

    hint = ClassifyHint(label="pothole", confidence=meta.local_confidence or 0.0)
    try:
        classification = classify_with_timeout(
            current_app.extensions["hazard_classifier"],
            image,
            hint,
            current_app.config["VLM_TIMEOUT_S"],
        )
    except ClassifierUnavailable as exc:
        raise ApiError(503, "vlm_unavailable", str(exc)) from exc

    store: ImageStore = current_app.extensions["image_store"]
    image_key = store.save(image)

    hazard = None
    outcome = "rejected"
    if classification.is_hazard:
        hazard, created = hazards.merge_or_create(
            lat=meta.lat,
            lng=meta.lng,
            hazard_type=classification.hazard_type,
            severity=classification.severity,
            seen_at=meta.captured_at,
            radius_m=current_app.config["HAZARD_MATCH_RADIUS_M"],
        )
        outcome = "created" if created else "matched"

    detection = Detection(
        client_detection_id=meta.client_detection_id,
        user_id=current_user().id,
        hazard=hazard,
        outcome=outcome,
        captured_at=meta.captured_at,
        received_at=utcnow(),
        lat=meta.lat,
        lng=meta.lng,
        heading_deg=meta.heading_deg,
        local_confidence=meta.local_confidence,
        image_key=image_key,
        is_hazard=classification.is_hazard,
        hazard_type=classification.hazard_type if classification.is_hazard else None,
        severity=classification.severity if classification.is_hazard else None,
        classifier_confidence=classification.confidence,
        model_id=classification.model_id,
    )
    db.session.add(detection)
    try:
        db.session.commit()
    except IntegrityError:
        # The same client_detection_id was committed by a concurrent retry.
        db.session.rollback()
        store.delete(image_key)
        existing = _existing(meta.client_detection_id)
        if existing is None:
            raise
        return jsonify(_result(existing))

    return jsonify(_result(detection))


@v1.delete("/detections/<detection_id>")
def undo_detection(detection_id: str):
    pk = parse_id(detection_id, DETECTION_PREFIX)
    detection = db.session.execute(
        db.select(Detection)
        .filter_by(id=pk, user_id=current_user().id, removed_at=None)
        .with_for_update()
    ).scalar_one_or_none()
    if detection is None:
        raise not_found("No detection with that id.")
    if utcnow() > _undo_expires_at(detection):
        raise ApiError(409, "undo_expired", "The undo window for this detection has closed.")

    hazards.release_sighting(detection)
    db.session.commit()
    return jsonify({"detection_id": detection_public_id(detection.id), "undone": True})
