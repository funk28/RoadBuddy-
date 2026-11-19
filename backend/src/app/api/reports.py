"""Contract calls 6 (GET /v1/me/reports) and 7 (DELETE /v1/me/reports/{id}),
plus the frame behind each report's image_url."""

from flask import current_app, jsonify, request, send_file, url_for

from app.api import v1
from app.auth import current_user
from app.errors import not_found
from app.extensions import db
from app.models import Detection, Hazard
from app.serialize import DETECTION_PREFIX, detection_public_id, hazard_public_id, iso, parse_id
from app.services import hazards
from app.services.storage import ImageStore

_BADGE_BY_OUTCOME = {"created": "submitted", "matched": "matched"}


def _badge(detection: Detection, hazard: Hazard) -> str:
    # A hazard that is no longer live (expired, or removed as "not a hazard") no
    # longer affects drivers, which the Reports screen shows as "expired".
    if hazard.status != "live":
        return "expired"
    return _BADGE_BY_OUTCOME[detection.outcome]


def _own_report(report_id: str, *, lock: bool = False) -> Detection:
    """The user's report with that id. Rejected and removed detections aren't reports."""
    pk = parse_id(report_id, DETECTION_PREFIX)
    query = db.select(Detection).where(
        Detection.id == pk,
        Detection.user_id == current_user().id,
        Detection.removed_at.is_(None),
        Detection.hazard_id.is_not(None),
    )
    if lock:
        query = query.with_for_update()
    detection = db.session.execute(query).scalar_one_or_none()
    if detection is None:
        raise not_found("No report with that id.")
    return detection


@v1.get("/me/reports")
def list_my_reports():
    query = (
        db.select(Detection, Hazard)
        .join(Hazard, Detection.hazard_id == Hazard.id)
        .where(Detection.user_id == current_user().id, Detection.removed_at.is_(None))
        .order_by(Detection.captured_at.desc(), Detection.id.desc())
    )
    trip_id = request.args.get("trip_id")
    if trip_id:
        query = query.where(Detection.trip_id == trip_id)

    reports = [
        {
            "id": detection_public_id(detection.id),
            "hazard_id": hazard_public_id(hazard.id),
            "type": hazard.type,
            "severity": hazard.severity,
            "captured_at": iso(detection.captured_at),
            "badge": _badge(detection, hazard),
            "image_url": url_for(
                "v1.my_report_image",
                report_id=detection_public_id(detection.id),
                _external=True,
            ),
        }
        for detection, hazard in db.session.execute(query)
    ]
    return jsonify({"reports": reports})


@v1.get("/me/reports/<report_id>/image")
def my_report_image(report_id: str):
    detection = _own_report(report_id)
    store: ImageStore = current_app.extensions["image_store"]
    path = store.path(detection.image_key)
    if not path.is_file():
        raise not_found("This report's image is no longer stored.")
    return send_file(path.resolve(), mimetype="image/jpeg", max_age=3600)


@v1.delete("/me/reports/<report_id>")
def delete_my_report(report_id: str):
    detection = _own_report(report_id, lock=True)
    hazard_removed = hazards.release_sighting(detection)
    db.session.commit()
    # The driver asked for this report to be gone, so its frame goes too.
    current_app.extensions["image_store"].delete(detection.image_key)
    return jsonify(
        {"id": detection_public_id(detection.id), "deleted": True, "hazard_removed": hazard_removed}
    )
