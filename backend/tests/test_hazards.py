import pytest
from conftest import CAMPUS, FAR, auth, meta, post_detection

from app.extensions import db
from app.models import Hazard
from app.seed import CAMPUS_HAZARDS, seed_hazards
from app.services.vlm_client import ClassificationResult

# Roughly 30 km away (Lafayette -> Frankfort), outside a 2 km radius.
ELSEWHERE = {"lat": 40.2795, "lng": -86.5108}


def list_hazards(client, user="user-a", **params):
    query = {"lat": CAMPUS["lat"], "lng": CAMPUS["lng"], "radius_m": 2000, **params}
    return client.get("/v1/hazards", query_string=query, headers=auth(user))


def report(client, location, severity="moderate", classifier=None, user="user-a"):
    if classifier is not None:
        classifier.result = ClassificationResult(True, "pothole", severity, 0.9, "fake")
    return post_detection(client, meta(location=location), user=user).get_json()


class TestListHazards:
    def test_returns_contract_shape_sorted_by_distance(self, client):
        far = report(client, FAR)
        here = report(client, CAMPUS)
        response = list_hazards(client)
        assert response.status_code == 200
        hazards = response.get_json()["hazards"]
        assert [h["id"] for h in hazards] == [here["hazard"]["id"], far["hazard"]["id"]]
        first = hazards[0]
        assert set(first) == {
            "id",
            "type",
            "severity",
            "lat",
            "lng",
            "distance_m",
            "sighting_count",
            "reported_by_me",
        }
        assert first["type"] == "pothole"
        assert first["distance_m"] == pytest.approx(0, abs=0.5)
        assert hazards[1]["distance_m"] == pytest.approx(100, abs=5)
        assert first["reported_by_me"] is True

    def test_excludes_hazards_outside_radius(self, client):
        report(client, ELSEWHERE)
        assert list_hazards(client).get_json()["hazards"] == []

    def test_excludes_removed_hazards(self, client):
        created = report(client, CAMPUS)
        client.delete(f"/v1/detections/{created['detection_id']}", headers=auth())
        assert list_hazards(client).get_json()["hazards"] == []

    def test_severity_filter(self, client, classifier):
        report(client, CAMPUS, "severe", classifier)
        report(client, FAR, "minor", classifier)
        hazards = list_hazards(client, severity="severe").get_json()["hazards"]
        assert [h["severity"] for h in hazards] == ["severe"]

    def test_mine_filter_and_reported_by_me(self, client):
        mine = report(client, CAMPUS, user="user-a")
        report(client, FAR, user="user-b")
        everything = list_hazards(client).get_json()["hazards"]
        assert {h["id"]: h["reported_by_me"] for h in everything} == {
            mine["hazard"]["id"]: True,
            everything[1]["id"]: False,
        }
        only_mine = list_hazards(client, mine="true").get_json()["hazards"]
        assert [h["id"] for h in only_mine] == [mine["hazard"]["id"]]

    def test_undone_report_no_longer_counts_as_mine(self, client):
        report(client, CAMPUS, user="user-b")
        mine = report(client, CAMPUS, user="user-a")
        client.delete(f"/v1/detections/{mine['detection_id']}", headers=auth())
        hazards = list_hazards(client).get_json()["hazards"]
        assert hazards[0]["reported_by_me"] is False
        assert list_hazards(client, mine="true").get_json()["hazards"] == []

    @pytest.mark.parametrize(
        "params",
        [
            {"lat": None},
            {"lng": "abc"},
            {"lat": 91},
            {"lng": -181},
            {"lat": "nan"},
            {"radius_m": None},
            {"radius_m": 0},
            {"radius_m": "1.5"},
            {"radius_m": -5},
            {"radius_m": 50_001},
            {"severity": "huge"},
            {"mine": "maybe"},
        ],
    )
    def test_bad_query_is_400(self, client, params):
        query = {"lat": CAMPUS["lat"], "lng": CAMPUS["lng"], "radius_m": 2000, **params}
        query = {k: val for k, val in query.items() if val is not None}
        response = client.get("/v1/hazards", query_string=query, headers=auth())
        assert response.status_code == 400
        assert response.get_json()["error"]["code"] == "invalid_request"

    def test_requires_auth(self, client):
        assert client.get("/v1/hazards?lat=0&lng=0&radius_m=10").status_code == 401


class TestUpdateHazard:
    def test_correct_severity(self, client, app):
        hazard_id = report(client, CAMPUS)["hazard"]["id"]
        response = client.patch(
            f"/v1/hazards/{hazard_id}", json={"severity": "minor"}, headers=auth()
        )
        assert response.status_code == 200
        assert response.get_json() == {"id": hazard_id, "severity": "minor", "status": "live"}
        assert list_hazards(client).get_json()["hazards"][0]["severity"] == "minor"

    def test_not_a_hazard_removes_it_from_the_list(self, client):
        hazard_id = report(client, CAMPUS)["hazard"]["id"]
        response = client.patch(
            f"/v1/hazards/{hazard_id}", json={"status": "removed"}, headers=auth()
        )
        assert response.get_json()["status"] == "removed"
        assert list_hazards(client).get_json()["hazards"] == []

    @pytest.mark.parametrize(
        "body",
        [{}, [], {"severity": "huge"}, {"status": "gone"}, {"severity": None}, {"lat": 1}],
    )
    def test_bad_body_is_400(self, client, body):
        hazard_id = report(client, CAMPUS)["hazard"]["id"]
        response = client.patch(f"/v1/hazards/{hazard_id}", json=body, headers=auth())
        assert response.status_code == 400

    def test_bad_body_changes_nothing(self, client):
        hazard_id = report(client, CAMPUS)["hazard"]["id"]
        client.patch(
            f"/v1/hazards/{hazard_id}",
            json={"severity": "minor", "status": "gone"},
            headers=auth(),
        )
        assert list_hazards(client).get_json()["hazards"][0]["severity"] == "moderate"

    @pytest.mark.parametrize("bad_id", ["h_999", "d_1", "45"])
    def test_unknown_hazard_is_404(self, client, bad_id):
        response = client.patch(f"/v1/hazards/{bad_id}", json={"severity": "minor"}, headers=auth())
        assert response.status_code == 404


class TestSeed:
    def test_seed_adds_campus_hazards_once(self, app):
        with app.app_context():
            assert seed_hazards() == len(CAMPUS_HAZARDS)
            assert seed_hazards() == 0
            assert db.session.scalar(db.select(db.func.count()).select_from(Hazard)) == len(
                CAMPUS_HAZARDS
            )

    def test_seeded_hazards_show_up_near_campus(self, app, client):
        with app.app_context():
            seed_hazards()
        hazards = list_hazards(client, radius_m=3000).get_json()["hazards"]
        assert len(hazards) == len(CAMPUS_HAZARDS)
        assert not any(h["reported_by_me"] for h in hazards)
