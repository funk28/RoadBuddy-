"""Mock the HTTP boundary; no API key, paid calls, or DB needed for unit tests."""

import base64
import json
from threading import Event
from unittest.mock import MagicMock

import pytest
import requests
from conftest import JPEG, auth, meta, post_detection

from app import create_app
from app.extensions import db
from app.models import Detection, Hazard
from app.services.vlm_client import (
    ClassificationResult,
    ClassifierUnavailable,
    ClassifyHint,
    OpenAIClassifier,
    StubClassifier,
    build_classifier,
    classify_with_timeout,
)

HINT = ClassifyHint("pothole", 0.81)
VALID = {"is_hazard": True, "hazard_type": "pothole", "severity": "moderate", "confidence": 0.92}
CONFIG = {
    "VLM_CLASSIFIER": "openai",
    "VLM_API_KEY": "test-only-key",
    "VLM_MODEL": "test-vision",
    "VLM_BASE_URL": "https://vision.example/v1/",
    "VLM_TIMEOUT_S": 0.5,
}


@pytest.fixture
def http(monkeypatch):
    response = MagicMock()
    response.__enter__.return_value = response
    response.json.return_value = {
        "choices": [{"finish_reason": "stop", "message": {"content": json.dumps(VALID)}}]
    }
    post = MagicMock(return_value=response)
    monkeypatch.setattr("app.services.vlm_client.requests.post", post)
    return post, response


@pytest.fixture
def real_classifier():
    return build_classifier(
        "openai",
        api_key=CONFIG["VLM_API_KEY"],
        model=CONFIG["VLM_MODEL"],
        base_url=CONFIG["VLM_BASE_URL"],
        timeout_s=CONFIG["VLM_TIMEOUT_S"],
    )


@pytest.mark.parametrize("severity", ["minor", "moderate", "severe"])
def test_pothole_and_request_payload(http, real_classifier, severity):
    post, response = http
    response.json.return_value["choices"][0]["message"]["content"] = json.dumps(
        dict(VALID, severity=severity)
    )
    assert real_classifier.classify(JPEG, HINT) == ClassificationResult(
        True, "pothole", severity, 0.92, "test-vision"
    )
    post.assert_called_once()
    assert post.call_args.args == ("https://vision.example/v1/chat/completions",)
    args = post.call_args.kwargs
    assert args["timeout"] == 0.5
    assert args["allow_redirects"] is False
    assert args["headers"] == {"Authorization": "Bearer test-only-key"}
    assert args["json"]["model"] == "test-vision"
    assert args["json"]["response_format"] == {"type": "json_object"}
    content = args["json"]["messages"][1]["content"]
    assert json.loads(content[0]["text"].removeprefix("Detector hint: ")) == {
        "label": "pothole",
        "confidence": 0.81,
    }
    assert content[1]["image_url"]["url"] == (
        "data:image/jpeg;base64," + base64.b64encode(JPEG).decode("ascii")
    )


@pytest.mark.parametrize("confidence", [0, 1])
def test_non_pothole(http, real_classifier, confidence):
    http[1].json.return_value["choices"][0]["message"]["content"] = json.dumps(
        dict(VALID, is_hazard=False, severity="minor", confidence=confidence)
    )
    assert real_classifier.classify(JPEG, HINT) == ClassificationResult(
        False, "pothole", "minor", confidence, "test-vision"
    )


@pytest.mark.parametrize(
    "content",
    [
        "not json",
        "```json\n{}\n```",
        "null",
        "[]",
        "{}",
        None,
        *[
            json.dumps(dict(VALID, **change))
            for change in [
                {"is_hazard": "false"},
                {"is_hazard": 1},
                {"hazard_type": "debris"},
                {"severity": "critical"},
                {"severity": None},
                {"confidence": "0.9"},
                {"confidence": True},
                {"confidence": -0.1},
                {"confidence": 1.1},
                {"confidence": float("nan")},
                {"confidence": float("inf")},
                {"is_hazard": False},
                {"model_id": "invented"},
            ]
        ],
        *[json.dumps({k: v for k, v in VALID.items() if k != missing}) for missing in VALID],
    ],
)
def test_invalid_model_output(http, real_classifier, content):
    http[1].json.return_value["choices"][0]["message"]["content"] = content
    with pytest.raises(ClassifierUnavailable, match="invalid response"):
        real_classifier.classify(JPEG, HINT)


@pytest.mark.parametrize(
    "envelope",
    [
        None,
        [],
        {},
        {"choices": []},
        {"choices": [None]},
        {"choices": [{"finish_reason": "length", "message": {"content": json.dumps(VALID)}}]},
        {"choices": [{"finish_reason": "stop", "message": {"refusal": "Cannot classify"}}]},
    ],
)
def test_invalid_api_response(http, real_classifier, envelope):
    http[1].json.return_value = envelope
    with pytest.raises(ClassifierUnavailable, match="invalid response"):
        real_classifier.classify(JPEG, HINT)


@pytest.mark.parametrize("error", [requests.Timeout, requests.ConnectionError, requests.HTTPError])
def test_api_failure(http, real_classifier, error):
    http[1].raise_for_status.side_effect = error("private provider details")
    with pytest.raises(ClassifierUnavailable, match="^Hazard classifier API request failed.$"):
        real_classifier.classify(JPEG, HINT)
    assert http[0].call_count == 1


def test_invalid_api_json(http, real_classifier):
    http[1].json.side_effect = ValueError("not JSON")
    with pytest.raises(ClassifierUnavailable, match="invalid response"):
        real_classifier.classify(JPEG, HINT)


def test_factory_keeps_stub_default_and_wires_real_config(http):
    assert isinstance(build_classifier("stub"), StubClassifier)
    assert build_classifier("stub").classify(JPEG, HINT).model_id == "stub-0.1"
    app = create_app({"SQLALCHEMY_DATABASE_URI": "sqlite://"})
    assert isinstance(app.extensions["hazard_classifier"], StubClassifier)
    app = create_app({"SQLALCHEMY_DATABASE_URI": "sqlite://", **CONFIG})
    classifier = app.extensions["hazard_classifier"]
    assert isinstance(classifier, OpenAIClassifier)
    assert classifier.classify(JPEG, HINT).model_id == "test-vision"
    with pytest.raises(ValueError, match="VLM_API_KEY"):
        build_classifier("openai")
    with pytest.raises(ValueError, match="Unknown VLM_CLASSIFIER"):
        build_classifier("typo")


@pytest.mark.parametrize(
    "options", [{"model": ""}, {"model": "x" * 65}, {"timeout_s": 0}, {"timeout_s": float("nan")}]
)
def test_bad_configuration(options):
    with pytest.raises(ValueError):
        build_classifier("openai", api_key="test-only-key", **options)


def test_existing_timeout_bounds_slow_http(http, real_classifier):
    release = Event()
    http[0].side_effect = lambda *args, **kwargs: (release.wait(1), http[1])[1]
    try:
        with pytest.raises(ClassifierUnavailable, match="did not respond in time"):
            classify_with_timeout(real_classifier, JPEG, HINT, timeout_s=0.01)
    finally:
        release.set()


@pytest.mark.parametrize("is_hazard", [True, False])
def test_real_adapter_in_detection_flow(client, app, http, real_classifier, is_hazard):
    app.extensions["hazard_classifier"] = real_classifier
    http[1].json.return_value["choices"][0]["message"]["content"] = json.dumps(
        dict(VALID, is_hazard=is_hazard, severity="minor")
    )
    response = post_detection(client, meta())
    assert response.status_code == 200
    result = response.get_json()
    assert result["outcome"] == ("created" if is_hazard else "rejected")
    hazards = client.get("/v1/hazards?lat=40.4237&lng=-86.9212&radius_m=100", headers=auth())
    reports = client.get("/v1/me/reports", headers=auth())
    assert hazards.status_code == reports.status_code == 200
    if is_hazard:
        assert result["hazard"]["severity"] == "minor"
        assert hazards.get_json()["hazards"][0]["severity"] == "minor"
        assert reports.get_json()["reports"][0]["severity"] == "minor"
    else:
        assert "hazard" not in result
        assert hazards.get_json()["hazards"] == reports.get_json()["reports"] == []
    with app.app_context():
        detection = db.session.execute(db.select(Detection)).scalar_one()
        assert detection.model_id == "test-vision"
        assert detection.classifier_confidence == 0.92


@pytest.mark.parametrize("failure", ["malformed", "http"])
def test_real_adapter_failure_is_503_without_storage(client, app, http, real_classifier, failure):
    app.extensions["hazard_classifier"] = real_classifier
    if failure == "http":
        http[0].side_effect = requests.ConnectionError("private provider details")
    else:
        http[1].json.return_value = {}
    response = post_detection(client, meta())
    assert response.status_code == 503
    assert response.get_json()["error"]["code"] == "vlm_unavailable"
    assert "private" not in response.get_data(as_text=True)
    with app.app_context():
        for model in (Detection, Hazard):
            assert db.session.scalar(db.select(db.func.count()).select_from(model)) == 0
    assert not list(app.extensions["image_store"].root.glob("*.jpg"))
