"""Tests run against a real PostGIS (ST_DWithin can't be faked): `docker compose up -d`.

TEST_DATABASE_URL points at a database that is wiped between tests.
"""

import json
import os
import uuid
from collections.abc import Iterator
from pathlib import Path

import pytest
from flask import Flask
from flask.testing import FlaskClient
from flask_migrate import upgrade
from sqlalchemy import text

from app import create_app
from app.auth import Identity
from app.errors import ApiError
from app.extensions import db
from app.services.vlm_client import ClassificationResult, ClassifyHint

TEST_DATABASE_URL = os.environ.get(
    "TEST_DATABASE_URL",
    "postgresql+psycopg://roadbuddy:roadbuddy@localhost:5433/roadbuddy_test",
)
MIGRATIONS_DIR = str(Path(__file__).resolve().parents[1] / "migrations")

# A minimal byte string that passes the JPEG magic-number check.
JPEG = b"\xff\xd8\xff\xe0" + b"\x00" * 32 + b"\xff\xd9"

# Purdue campus, and points about 5 m and 100 m north of it.
CAMPUS = {"lat": 40.4237, "lng": -86.9212}
NEAR = {"lat": 40.42374, "lng": -86.9212}
FAR = {"lat": 40.4246, "lng": -86.9212}


class FakeClassifier:
    """Returns `result`, or raises `error`; counts calls."""

    def __init__(self) -> None:
        self.calls = 0
        self.error: Exception | None = None
        self.result = ClassificationResult(
            is_hazard=True,
            hazard_type="pothole",
            severity="moderate",
            confidence=0.9,
            model_id="fake",
        )

    def classify(self, image: bytes, hint: ClassifyHint) -> ClassificationResult:
        self.calls += 1
        if self.error is not None:
            raise self.error
        return self.result


def fake_verifier(token: str) -> Identity:
    """Any token `user-*` is a valid user with that subject."""
    if not token.startswith("user-"):
        raise ApiError(401, "unauthorized", "Invalid Google ID token.")
    return Identity(subject=token, email=f"{token}@example.com")


@pytest.fixture(scope="session")
def _migrated() -> None:
    app = create_app({"SQLALCHEMY_DATABASE_URI": TEST_DATABASE_URL})
    with app.app_context():
        db.session.execute(text("DROP TABLE IF EXISTS alembic_version"))
        for table in ("user_settings", "detections", "hazards", "users"):
            db.session.execute(text(f"DROP TABLE IF EXISTS {table} CASCADE"))
        db.session.commit()
        upgrade(directory=MIGRATIONS_DIR)


@pytest.fixture
def classifier() -> FakeClassifier:
    return FakeClassifier()


@pytest.fixture
def app(_migrated: None, classifier: FakeClassifier, tmp_path: Path) -> Iterator[Flask]:
    app = create_app(
        {
            "TESTING": True,
            "SQLALCHEMY_DATABASE_URI": TEST_DATABASE_URL,
            "IMAGE_STORAGE_DIR": str(tmp_path / "images"),
            "DEV_AUTH": False,
            "VLM_TIMEOUT_S": 1,
        },
        classifier=classifier,
        token_verifier=fake_verifier,
    )
    yield app
    with app.app_context():
        db.session.remove()
        db.session.execute(
            text("TRUNCATE user_settings, detections, hazards, users RESTART IDENTITY CASCADE")
        )
        db.session.commit()


@pytest.fixture
def client(app: Flask) -> FlaskClient:
    return app.test_client()


def auth(user: str = "user-a") -> dict[str, str]:
    return {"Authorization": f"Bearer {user}"}


def meta(**overrides) -> dict:
    body = {
        "client_detection_id": str(uuid.uuid4()),
        "captured_at": "2026-11-13T14:48:02Z",
        "location": CAMPUS,
        "heading_deg": 92,
        "local_confidence": 0.81,
    }
    body.update(overrides)
    return body


def post_detection(
    client: FlaskClient,
    body: dict | str | None = None,
    *,
    user: str = "user-a",
    image: bytes | None = JPEG,
):
    import io

    data: dict = {}
    if body is not None:
        data["meta"] = body if isinstance(body, str) else json.dumps(body)
    if image is not None:
        data["image"] = (io.BytesIO(image), "frame.jpg", "image/jpeg")
    return client.post(
        "/v1/detections", data=data, headers=auth(user), content_type="multipart/form-data"
    )
