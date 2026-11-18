import time

import pytest
from conftest import CAMPUS, FAR, NEAR, auth, meta, post_detection

from app.extensions import db
from app.models import Detection, Hazard
from app.services.vlm_client import ClassificationResult


def _hazard_count(app) -> int:
    with app.app_context():
        return db.session.scalar(db.select(db.func.count()).select_from(Hazard))


def _detection_count(app) -> int:
    with app.app_context():
        return db.session.scalar(db.select(db.func.count()).select_from(Detection))


class TestReportDetection:
    def test_first_upload_creates_hazard(self, client):
        response = post_detection(client, meta())
        assert response.status_code == 200
        body = response.get_json()
        assert body["outcome"] == "created"
        assert body["detection_id"].startswith("d_")
        assert body["hazard"] == {
            "id": body["hazard"]["id"],
            "severity": "moderate",
            "lat": CAMPUS["lat"],
            "lng": CAMPUS["lng"],
            "sighting_count": 1,
        }
        assert body["hazard"]["id"].startswith("h_")
        assert body["undo_expires_at"].endswith("Z")

    def test_nearby_upload_matches_existing_hazard(self, client, app):
        first = post_detection(client, meta(location=CAMPUS)).get_json()
        second = post_detection(client, meta(location=NEAR), user="user-b").get_json()
        assert second["outcome"] == "matched"
        assert second["hazard"]["id"] == first["hazard"]["id"]
        assert second["hazard"]["sighting_count"] == 2
        assert _hazard_count(app) == 1

    def test_far_upload_creates_separate_hazard(self, client, app):
        first = post_detection(client, meta(location=CAMPUS)).get_json()
        second = post_detection(client, meta(location=FAR)).get_json()
        assert second["outcome"] == "created"
        assert second["hazard"]["id"] != first["hazard"]["id"]
        assert _hazard_count(app) == 2

    def test_match_keeps_the_worse_severity(self, client, classifier):
        post_detection(client, meta(location=CAMPUS))
        classifier.result = ClassificationResult(True, "pothole", "severe", 0.9, "fake")
        assert post_detection(client, meta(location=NEAR)).get_json()["hazard"]["severity"] == (
            "severe"
        )
        classifier.result = ClassificationResult(True, "pothole", "minor", 0.9, "fake")
        assert post_detection(client, meta(location=NEAR)).get_json()["hazard"]["severity"] == (
            "severe"
        )

    def test_retry_with_same_client_id_returns_original_and_skips_classifier(
        self, client, app, classifier
    ):
        body = meta()
        first = post_detection(client, body).get_json()
        retry = post_detection(client, body)
        assert retry.status_code == 200
        assert retry.get_json()["detection_id"] == first["detection_id"]
        assert retry.get_json()["outcome"] == "created"
        assert classifier.calls == 1
        assert _detection_count(app) == 1
        assert _hazard_count(app) == 1

    def test_client_id_reused_by_another_user_is_rejected(self, client):
        body = meta()
        post_detection(client, body)
        response = post_detection(client, body, user="user-b")
        assert response.status_code == 400
        assert response.get_json()["error"]["code"] == "client_detection_id_conflict"

    def test_classifier_says_not_a_hazard(self, client, app, classifier):
        classifier.result = ClassificationResult(False, "pothole", "minor", 0.7, "fake")
        body = post_detection(client, meta()).get_json()
        assert body["outcome"] == "rejected"
        assert "hazard" not in body
        assert "undo_expires_at" not in body
        assert _hazard_count(app) == 0
        assert _detection_count(app) == 1

    def test_frame_is_stored(self, client, app):
        post_detection(client, meta())
        with app.app_context():
            detection = db.session.execute(db.select(Detection)).scalar_one()
            assert app.extensions["image_store"].path(detection.image_key).read_bytes()[:3] == (
                b"\xff\xd8\xff"
            )


class TestClassifierFailures:
    def test_classifier_error_is_503_and_nothing_is_stored(self, client, app, classifier):
        classifier.error = RuntimeError("boom")
        response = post_detection(client, meta())
        assert response.status_code == 503
        assert response.get_json()["error"]["code"] == "vlm_unavailable"
        assert _detection_count(app) == 0

    def test_classifier_timeout_is_503_then_retry_succeeds(self, client, app, classifier):
        class Slow:
            def classify(self, image, hint):
                time.sleep(2)

        app.extensions["hazard_classifier"] = Slow()
        body = meta()
        response = post_detection(client, body)
        assert response.status_code == 503
        assert response.get_json()["error"]["code"] == "vlm_unavailable"

        app.extensions["hazard_classifier"] = classifier
        assert post_detection(client, body).get_json()["outcome"] == "created"


class TestValidation:
    @pytest.mark.parametrize(
        "body",
        [
            None,
            "not json",
            "[1, 2]",
            {k: v for k, v in meta().items() if k != "client_detection_id"},
            meta(client_detection_id=""),
            meta(client_detection_id="x" * 129),
            {k: v for k, v in meta().items() if k != "captured_at"},
            meta(captured_at="yesterday"),
            meta(captured_at="2026-11-13T14:48:02"),  # no UTC offset
            {k: v for k, v in meta().items() if k != "location"},
            meta(location={"lat": 91, "lng": 0}),
            meta(location={"lat": 0, "lng": -180.5}),
            meta(location={"lat": True, "lng": 0}),
            meta(location={"lat": "40.1", "lng": 0}),
            meta(location={"lat": 40.1}),
            meta(heading_deg=360),
            meta(heading_deg=-1),
            meta(local_confidence=1.5),
        ],
    )
    def test_bad_meta_is_400(self, client, classifier, body):
        response = post_detection(client, body)
        assert response.status_code == 400, response.get_json()
        assert set(response.get_json()["error"]) == {"code", "message"}
        assert classifier.calls == 0

    def test_nan_coordinates_are_400(self, client):
        response = post_detection(
            client,
            '{"client_detection_id": "a", "captured_at": "2026-11-13T14:48:02Z", '
            '"location": {"lat": NaN, "lng": 0}}',
        )
        assert response.status_code == 400

    def test_optional_fields_can_be_left_out(self, client):
        body = {k: v for k, v in meta().items() if k not in ("heading_deg", "local_confidence")}
        assert post_detection(client, body).status_code == 200

    def test_missing_image_is_400(self, client):
        assert post_detection(client, meta(), image=None).status_code == 400

    def test_non_jpeg_image_is_400(self, client):
        assert post_detection(client, meta(), image=b"\x89PNG\r\n").status_code == 400


class TestUndoDetection:
    def test_undo_only_sighting_removes_hazard(self, client, app):
        created = post_detection(client, meta()).get_json()
        response = client.delete(f"/v1/detections/{created['detection_id']}", headers=auth())
        assert response.status_code == 200
        assert response.get_json() == {"detection_id": created["detection_id"], "undone": True}
        with app.app_context():
            hazard = db.session.execute(db.select(Hazard)).scalar_one()
            assert (hazard.status, hazard.sighting_count) == ("removed", 0)

    def test_undo_matched_sighting_decrements_hazard(self, client, app):
        post_detection(client, meta(location=CAMPUS))
        matched = post_detection(client, meta(location=NEAR)).get_json()
        client.delete(f"/v1/detections/{matched['detection_id']}", headers=auth())
        with app.app_context():
            hazard = db.session.execute(db.select(Hazard)).scalar_one()
            assert (hazard.status, hazard.sighting_count) == ("live", 1)

    def test_removed_hazard_is_not_matched_again(self, client):
        first = post_detection(client, meta()).get_json()
        client.delete(f"/v1/detections/{first['detection_id']}", headers=auth())
        again = post_detection(client, meta(location=NEAR)).get_json()
        assert again["outcome"] == "created"
        assert again["hazard"]["id"] != first["hazard"]["id"]

    def test_undo_twice_is_404(self, client):
        created = post_detection(client, meta()).get_json()
        client.delete(f"/v1/detections/{created['detection_id']}", headers=auth())
        response = client.delete(f"/v1/detections/{created['detection_id']}", headers=auth())
        assert response.status_code == 404

    def test_cannot_undo_another_users_detection(self, client):
        created = post_detection(client, meta()).get_json()
        response = client.delete(
            f"/v1/detections/{created['detection_id']}", headers=auth("user-b")
        )
        assert response.status_code == 404

    @pytest.mark.parametrize("bad_id", ["123", "h_1", "d_", "d_abc", "d_99999"])
    def test_unknown_or_malformed_id_is_404(self, client, bad_id):
        assert client.delete(f"/v1/detections/{bad_id}", headers=auth()).status_code == 404

    def test_undo_after_window_is_409(self, client, app):
        app.config["UNDO_WINDOW_S"] = -1
        created = post_detection(client, meta()).get_json()
        response = client.delete(f"/v1/detections/{created['detection_id']}", headers=auth())
        assert response.status_code == 409
        assert response.get_json()["error"]["code"] == "undo_expired"
