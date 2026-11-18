from flask import Blueprint

from app.auth import authenticate

v1 = Blueprint("v1", __name__, url_prefix="/v1")
v1.before_request(authenticate)

# Route modules register themselves on `v1` when imported.
from app.api import detections, hazards  # noqa: E402, F401
