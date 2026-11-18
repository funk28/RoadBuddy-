import pytest
from conftest import CAMPUS, FAR, JPEG, NEAR, auth, meta, post_detection

from app.extensions import db
from app.models import Hazard
from app.services.vlm_client import ClassificationResult


def my_reports(client, user="user-a", **params):
    return client.get("/v1/me/reports", query_string=params, headers=auth(user))


class TestListMyReports:
    def test_contract_shape_newest_first(self, client):
        older = post_detection(client, meta(captured_at="2026-11-13T14:00:00Z")).get_json()
        newer = post_detection(
            client, meta(location=FAR, captured_at="2026-11-13T15:00:00Z")
        ).get_json()
        response = my_reports(client)
        assert response.status_code == 200
        reports = response.get_json()["reports"]
        assert [r["id"] for r in reports] == [newer["detection_id"], older["detection_id"]]
        first = reports[0]
        assert first == {
            "id": newer["detection_id"],
            "hazard_id": newer["hazard"]["id"],
            "type": "pothole",
            "severity": "moderate",
            "captured_at": "2026-11-13T15:00:00Z",
            "badge": "submitted",
            "image_url": f"http://localhost/v1/me/reports/{newer['detection_id']}/image",
        }

    def test_badges(self, client):
        post_detection(client, meta(location=CAMPUS), user="user-b")
        matched = post_detection(client, meta(location=NEAR)).get_json()
        submitted = post_detection(client, meta(location=FAR)).get_json()
        badges = {r["id"]: r["badge"] for r in my_reports(client).get_json()["reports"]}
        assert badges == {
            matched["detection_id"]: "matched",
            submitted["detection_id"]: "submitted",
        }

    def test_expired_hazard_gives_expired_badge(self, client, app):
        created = post_detection(client, meta()).get_json()
        with app.app_context():
            db.session.execute(db.update(Hazard).values(status="expired"))
            db.session.commit()
        assert my_reports(client).get_json()["reports"][0]["badge"] == "expired"
        assert created["detection_id"] == my_reports(client).get_json()["reports"][0]["id"]

    def test_severity_comes_from_the_classifier(self, client, classifier):
        classifier.result = ClassificationResult(True, "pothole", "severe", 0.9, "fake")
        post_detection(client, meta())
        assert my_reports(client).get_json()["reports"][0]["severity"] == "severe"

    def test_only_my_reports(self, client):
        post_detection(client, meta(), user="user-b")
        assert my_reports(client).get_json()["reports"] == []

    def test_rejected_and_undone_detections_are_not_reports(self, client, classifier):
        undone = post_detection(client, meta()).get_json()
        client.delete(f"/v1/detections/{undone['detection_id']}", headers=auth())
        classifier.result = ClassificationResult(False, "pothole", "minor", 0.5, "fake")
        post_detection(client, meta(location=FAR))
        assert my_reports(client).get_json()["reports"] == []

    def test_trip_filter(self, client):
        this_trip = post_detection(client, meta(trip_id="t_9")).get_json()
        post_detection(client, meta(location=FAR, trip_id="t_8"))
        post_detection(client, meta(location=FAR))
        reports = my_reports(client, trip_id="t_9").get_json()["reports"]
        assert [r["id"] for r in reports] == [this_trip["detection_id"]]
        assert len(my_reports(client).get_json()["reports"]) == 3

    @pytest.mark.parametrize("trip_id", ["", 9, "x" * 65])
    def test_bad_trip_id_in_meta_is_400(self, client, trip_id):
        assert post_detection(client, meta(trip_id=trip_id)).status_code == 400

    def test_requires_auth(self, client):
        assert client.get("/v1/me/reports").status_code == 401


class TestReportImage:
    def test_serves_the_stored_frame(self, client):
        created = post_detection(client, meta()).get_json()
        url = my_reports(client).get_json()["reports"][0]["image_url"]
        response = client.get(url, headers=auth())
        assert response.status_code == 200
        assert response.mimetype == "image/jpeg"
        assert response.data == JPEG
        assert created["detection_id"] in url

    def test_other_users_cannot_fetch_it(self, client):
        created = post_detection(client, meta()).get_json()
        response = client.get(
            f"/v1/me/reports/{created['detection_id']}/image", headers=auth("user-b")
        )
        assert response.status_code == 404

    def test_requires_auth(self, client):
        created = post_detection(client, meta()).get_json()
        assert client.get(f"/v1/me/reports/{created['detection_id']}/image").status_code == 401


class TestDeleteMyReport:
    def test_deleting_only_sighting_removes_hazard(self, client, app):
        created = post_detection(client, meta()).get_json()
        response = client.delete(f"/v1/me/reports/{created['detection_id']}", headers=auth())
        assert response.status_code == 200
        assert response.get_json() == {
            "id": created["detection_id"],
            "deleted": True,
            "hazard_removed": True,
        }
        assert my_reports(client).get_json()["reports"] == []
        with app.app_context():
            hazard = db.session.execute(db.select(Hazard)).scalar_one()
            assert (hazard.status, hazard.sighting_count) == ("removed", 0)

    def test_deleting_one_of_several_sightings_keeps_hazard(self, client, app):
        post_detection(client, meta(location=CAMPUS), user="user-b")
        mine = post_detection(client, meta(location=NEAR)).get_json()
        body = client.delete(f"/v1/me/reports/{mine['detection_id']}", headers=auth()).get_json()
        assert body["hazard_removed"] is False
        with app.app_context():
            hazard = db.session.execute(db.select(Hazard)).scalar_one()
            assert (hazard.status, hazard.sighting_count) == ("live", 1)

    def test_works_after_the_undo_window(self, client, app):
        app.config["UNDO_WINDOW_S"] = -1
        created = post_detection(client, meta()).get_json()
        response = client.delete(f"/v1/me/reports/{created['detection_id']}", headers=auth())
        assert response.status_code == 200

    def test_deletes_the_frame(self, client):
        created = post_detection(client, meta()).get_json()
        client.delete(f"/v1/me/reports/{created['detection_id']}", headers=auth())
        response = client.get(f"/v1/me/reports/{created['detection_id']}/image", headers=auth())
        assert response.status_code == 404

    def test_delete_twice_is_404(self, client):
        created = post_detection(client, meta()).get_json()
        client.delete(f"/v1/me/reports/{created['detection_id']}", headers=auth())
        response = client.delete(f"/v1/me/reports/{created['detection_id']}", headers=auth())
        assert response.status_code == 404

    def test_cannot_delete_another_users_report(self, client):
        created = post_detection(client, meta(), user="user-b").get_json()
        response = client.delete(f"/v1/me/reports/{created['detection_id']}", headers=auth())
        assert response.status_code == 404

    @pytest.mark.parametrize("bad_id", ["d_999", "h_1", "abc"])
    def test_unknown_id_is_404(self, client, bad_id):
        assert client.delete(f"/v1/me/reports/{bad_id}", headers=auth()).status_code == 404
