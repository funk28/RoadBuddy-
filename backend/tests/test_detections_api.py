import json
from io import BytesIO
from unittest.mock import Mock

import pytest

from app import create_app
from app.db.memory import MemoryRepository
from app.services.classifier import StubClassifier
from app.services.geo import offset_m

CAPTURED = "2026-11-13T14:48:02Z"


def _client(radius_m=25, classifier=None):
    repo = MemoryRepository()
    app = create_app(
        repository=repo,
        classifier=classifier,
        config={"TESTING": True, "HAZARD_MATCH_RADIUS_M": radius_m, "HAZARD_TTL_HOURS": 24},
    )
    return app.test_client(), repo


def _post(client, client_detection_id, lat, lng, **extra):
    body = {
        "client_detection_id": client_detection_id,
        "captured_at": CAPTURED,
        "location": {"lat": lat, "lng": lng},
        "hazard_type": "pothole",
        "severity": "severe",
        "local_confidence": 0.81,
    }
    body.update(extra)
    return client.post("/v1/detections", json=body)


def test_post_creates_then_matches_and_retries_without_a_new_hazard():
    client, repo = _client()
    created = _post(client, "det-1", 40.4237, -86.9212)
    assert created.status_code == 200
    created_body = created.get_json()
    assert created_body["outcome"] == "created"
    assert created_body["hazard"]["sighting_count"] == 1
    assert created_body["hazard"]["severity"] == "severe"
    assert created_body["undo_expires_at"] == "2026-11-13T14:48:07Z"

    lat, lng = offset_m(40.4237, -86.9212, 12)
    matched = _post(
        client,
        "det-2",
        lat,
        lng,
        captured_at="2026-11-13T16:48:02Z",
        severity="minor",
    )
    matched_body = matched.get_json()
    assert matched_body["outcome"] == "matched"
    assert matched_body["hazard"]["id"] == created_body["hazard"]["id"]
    assert matched_body["hazard"]["sighting_count"] == 2
    assert matched_body["hazard"]["severity"] == "severe"
    assert matched_body["hazard"]["expires_at"] == "2026-11-14T16:48:02Z"

    retry = _post(client, "det-1", 40.4237, -86.9212)
    retry_body = retry.get_json()
    assert retry_body["outcome"] == "created"
    assert retry_body["detection_id"] == created_body["detection_id"]
    assert retry_body["hazard"]["sighting_count"] == 2
    assert len(repo.list_hazards()) == 1
    assert repo.detection_count() == 2


def test_post_rejects_a_non_hazard_and_validates_the_body():
    client, repo = _client()
    rejected = _post(client, "det-1", 40.0, -86.0, is_hazard=False)
    assert rejected.get_json()["outcome"] == "rejected"
    assert rejected.get_json()["hazard"] is None
    assert repo.list_hazards() == []

    missing = client.post("/v1/detections", json={"location": {"lat": 1, "lng": 2}})
    assert missing.status_code == 400
    assert missing.get_json()["error"]["code"] == "missing_field"


def test_post_outside_the_configured_radius_creates_a_second_hazard():
    client, repo = _client()
    _post(client, "origin", 40.0, -86.0)
    lat, lng = offset_m(40.0, -86.0, 26)
    outside = _post(client, "far", lat, lng)
    assert outside.get_json()["outcome"] == "created"
    assert len(repo.list_hazards()) == 2


@pytest.fixture(params=[False, True], ids=["empty-store", "populated-store"])
def coordinate_context(request):
    classifier = Mock(wraps=StubClassifier())
    client, repo = _client(classifier=classifier)
    if request.param:
        assert _post(client, "seed", 10, 10).status_code == 200
    classifier.reset_mock()
    return client, repo, classifier


@pytest.fixture(params=["json", "multipart"])
def request_format(request):
    return request.param


def _send_meta(client, serialized_meta, request_format):
    if request_format == "json":
        return client.post("/v1/detections", data=serialized_meta, content_type="application/json")
    return client.post(
        "/v1/detections",
        data={"meta": serialized_meta, "image": (BytesIO(b"test-image"), "frame.jpg", "image/jpeg")},
        content_type="multipart/form-data",
    )


def _coordinate_payload(location):
    return {
        "client_detection_id": "candidate",
        "captured_at": CAPTURED,
        "location": location,
    }


def _assert_invalid_coordinates(context, serialized_meta, request_format, code, field):
    client, repo, classifier = context
    hazards_before = repo.list_hazards()
    count_before = repo.detection_count()
    detection_before = repo.get_detection_by_client_id("seed")

    response = _send_meta(client, serialized_meta, request_format)

    assert response.status_code == 400
    error = response.get_json()["error"]
    assert error["code"] == code
    assert field in error["message"]
    classifier.classify.assert_not_called()
    assert repo.list_hazards() == hazards_before
    assert repo.detection_count() == count_before
    assert repo.get_detection_by_client_id("seed") == detection_before
    assert repo.get_detection_by_client_id("candidate") is None


# Raw tokens exercise the HTTP decoder, including nonstandard constants accepted
# by Python's JSON decoder and valid JSON exponents that overflow to infinity.
INVALID_COORDINATE_TOKENS = [
    ("nan", "NaN"),
    ("infinity", "Infinity"),
    ("negative-infinity", "-Infinity"),
    ("overflow", "1e400"),
    ("negative-overflow", "-1e400"),
    ("huge-integer", str(10**400)),
    ("negative-huge-integer", str(-(10**400))),
    ("numeric-string", '"40.0"'),
    ("nan-string", '"NaN"'),
    ("infinity-string", '"Infinity"'),
    ("empty-string", '""'),
    ("text", '"invalid"'),
    ("true", "true"),
    ("false", "false"),
    ("null", "null"),
    ("array", "[]"),
    ("object", "{}"),
]


@pytest.mark.parametrize(
    "field,raw_value",
    [
        pytest.param(field, token, id=f"{field}-{name}")
        for field in ("lat", "lng")
        for name, token in INVALID_COORDINATE_TOKENS
    ]
    + [
        pytest.param("lat", "90.000001", id="lat-above-maximum"),
        pytest.param("lat", "-90.000001", id="lat-below-minimum"),
        pytest.param("lng", "180.000001", id="lng-above-maximum"),
        pytest.param("lng", "-180.000001", id="lng-below-minimum"),
    ],
)
def test_invalid_coordinate_values(coordinate_context, request_format, field, raw_value):
    location = {"lat": 40.4237, "lng": -86.9212, field: "COORDINATE"}
    serialized_meta = json.dumps(_coordinate_payload(location)).replace('"COORDINATE"', raw_value)
    _assert_invalid_coordinates(
        coordinate_context, serialized_meta, request_format, "invalid_field", f"location.{field}",
    )


@pytest.mark.parametrize(
    "location_fields,code,field",
    [
        pytest.param({}, "missing_field", "location", id="missing-location"),
        pytest.param({"location": {}}, "missing_field", "location.lat", id="empty-location"),
        pytest.param({"location": {"lng": 0}}, "missing_field", "location.lat", id="missing-lat"),
        pytest.param({"location": {"lat": 0}}, "missing_field", "location.lng", id="missing-lng"),
    ]
    + [
        pytest.param({"location": value}, "invalid_field", "location", id=f"location-{name}")
        for name, value in (
            ("null", None), ("array", []), ("string", "invalid"), ("number", 1), ("boolean", True),
        )
    ],
)
def test_invalid_location_structure(coordinate_context, request_format, location_fields, code, field):
    payload = {"client_detection_id": "candidate", "captured_at": CAPTURED, **location_fields}
    _assert_invalid_coordinates(coordinate_context, json.dumps(payload), request_format, code, field)


@pytest.mark.parametrize(
    "lat,lng",
    [
        pytest.param(40.4237, -86.9212, id="ordinary"),
        pytest.param(40, -86, id="integers"),
        pytest.param(0, 0, id="zero"),
        pytest.param(-0.0, -0.0, id="negative-zero"),
        pytest.param(-90, -180, id="minimums"),
        pytest.param(90, 180, id="maximums"),
        pytest.param(-90, 180, id="south-east"),
        pytest.param(90, -180, id="north-west"),
    ],
)
def test_valid_coordinates(coordinate_context, request_format, lat, lng):
    client, repo, classifier = coordinate_context
    count_before = repo.detection_count()
    payload = _coordinate_payload({"lat": lat, "lng": lng})

    response = _send_meta(client, json.dumps(payload), request_format)

    assert response.status_code == 200
    body = response.get_json()
    assert body["outcome"] == "created"
    assert body["hazard"]["lat"] == lat
    assert body["hazard"]["lng"] == lng
    classifier.classify.assert_called_once()
    assert repo.detection_count() == count_before + 1
    detection = repo.get_detection_by_client_id("candidate")
    assert detection.lat == lat
    assert detection.lng == lng
    assert isinstance(detection.lat, float)
    assert isinstance(detection.lng, float)
    hazard = repo.get_hazard(detection.hazard_id)
    assert (hazard.lat, hazard.lng) == (lat, lng)
