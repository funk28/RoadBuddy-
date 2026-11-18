from typing import Any

from flask import Flask

from app.auth import TokenVerifier, google_verifier
from app.config import Config
from app.errors import register_error_handlers
from app.extensions import db, migrate
from app.services.storage import ImageStore
from app.services.vlm_client import HazardClassifier, build_classifier


def create_app(
    config: dict[str, Any] | None = None,
    *,
    classifier: HazardClassifier | None = None,
    token_verifier: TokenVerifier | None = None,
) -> Flask:
    """App factory. Tests pass a config and fakes; production reads the environment."""
    app = Flask(__name__)
    app.config.from_object(Config)
    if config:
        app.config.update(config)

    db.init_app(app)
    migrate.init_app(app, db)
    register_error_handlers(app)

    app.extensions["hazard_classifier"] = classifier or build_classifier(
        app.config["VLM_CLASSIFIER"]
    )
    app.extensions["token_verifier"] = token_verifier or google_verifier(
        app.config["GOOGLE_CLIENT_IDS"]
    )
    app.extensions["image_store"] = ImageStore(app.config["IMAGE_STORAGE_DIR"])

    from app import models  # noqa: F401  (registers the tables for migrations)
    from app.api import v1

    app.register_blueprint(v1)
    return app
