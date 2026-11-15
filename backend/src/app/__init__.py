import os

from flask import Flask

from app.api.detections import detections_bp
from app.db.memory import MemoryRepository
from app.services.classifier import StubClassifier


def create_app(repository=None, classifier=None, config=None) -> Flask:
    app = Flask(__name__)
    app.config["HAZARD_MATCH_RADIUS_M"] = float(os.environ.get("HAZARD_MATCH_RADIUS_M", "25"))
    app.config["HAZARD_TTL_HOURS"] = float(os.environ.get("HAZARD_TTL_HOURS", "24"))
    if config:
        app.config.update(config)

    if repository is None:
        database_url = os.environ.get("DATABASE_URL")
        if database_url:
            from app.db.postgis import PostgisRepository

            repository = PostgisRepository(database_url)
        else:
            repository = MemoryRepository()

    app.extensions["hazard_repository"] = repository
    app.extensions["hazard_classifier"] = classifier or StubClassifier()
    app.register_blueprint(detections_bp, url_prefix="/v1")
    return app
