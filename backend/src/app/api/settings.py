"""Contract call 8: GET / PUT /v1/me/settings."""

from flask import jsonify, request
from sqlalchemy.dialects.postgresql import insert

from app.api import v1
from app.api import validation as v
from app.auth import current_user
from app.errors import bad_request
from app.extensions import db
from app.models import UserSettings, utcnow
from app.serialize import iso

# What a user gets before they've saved anything.
DEFAULT_SETTINGS = {
    "audible_alerts": True,
    "detection_enabled": True,
    "suggest_reroutes": True,
}
FIELDS = tuple(DEFAULT_SETTINGS)


def _body(values: dict[str, bool], updated_at) -> dict:
    return {**values, "updated_at": iso(updated_at)}


@v1.get("/me/settings")
def get_settings():
    user = current_user()
    saved = db.session.get(UserSettings, user.id)
    if saved is None:
        # Never saved: the defaults, as of when the account was created.
        return jsonify(_body(DEFAULT_SETTINGS, user.created_at))
    return jsonify(_body({f: getattr(saved, f) for f in FIELDS}, saved.updated_at))


@v1.put("/me/settings")
def save_settings():
    body = request.get_json(silent=True)
    if not isinstance(body, dict):
        raise bad_request("Send a JSON object with the settings.")
    missing = [f for f in FIELDS if f not in body]
    if missing:
        raise bad_request(f"Missing field(s): {', '.join(missing)}.")
    # Unknown fields are ignored, so an app that has a newer setting than this
    # server still syncs the ones it knows instead of retrying forever.
    values = {f: v.boolean(body[f], f) for f in FIELDS}

    now = utcnow()
    statement = insert(UserSettings).values(user_id=current_user().id, updated_at=now, **values)
    db.session.execute(
        statement.on_conflict_do_update(
            index_elements=[UserSettings.user_id], set_={**values, "updated_at": now}
        )
    )
    db.session.commit()
    return jsonify(_body(values, now))
