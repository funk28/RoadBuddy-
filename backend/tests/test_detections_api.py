from app import create_app
from app.db.memory import MemoryRepository
from app.services.geo import offset_m

CAPTURED = "2026-11-13T14:48:02Z"


def _client(radius_m=25):
    repo = MemoryRepository()
    app = create_app(repository=repo, config={"HAZARD_MATCH_RADIUS_M": radius_m, "HAZARD_TTL_HOURS": 24})
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
