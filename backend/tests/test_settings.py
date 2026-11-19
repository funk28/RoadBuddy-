from datetime import datetime

import pytest
from conftest import auth

ALL_OFF = {"audible_alerts": False, "detection_enabled": False, "suggest_reroutes": False}


def get(client, user="user-a"):
    return client.get("/v1/me/settings", headers=auth(user))


def put(client, body, user="user-a"):
    return client.put("/v1/me/settings", json=body, headers=auth(user))


def test_defaults_before_anything_is_saved(client):
    response = get(client)
    assert response.status_code == 200
    body = response.get_json()
    assert {k: body[k] for k in ("audible_alerts", "detection_enabled", "suggest_reroutes")} == {
        "audible_alerts": True,
        "detection_enabled": True,
        "suggest_reroutes": True,
    }
    assert body["updated_at"].endswith("Z")


def test_put_returns_settings_with_updated_at(client):
    response = put(client, ALL_OFF)
    assert response.status_code == 200
    body = response.get_json()
    assert {k: v for k, v in body.items() if k != "updated_at"} == ALL_OFF
    datetime.fromisoformat(body["updated_at"].replace("Z", "+00:00"))


def test_saved_settings_survive_and_can_be_changed(client):
    put(client, ALL_OFF)
    assert {k: v for k, v in get(client).get_json().items() if k != "updated_at"} == ALL_OFF
    put(client, {**ALL_OFF, "audible_alerts": True})
    assert get(client).get_json()["audible_alerts"] is True
    assert get(client).get_json()["detection_enabled"] is False


def test_get_returns_what_put_returned(client):
    saved = put(client, ALL_OFF).get_json()
    assert get(client).get_json() == saved


def test_settings_are_per_user(client):
    put(client, ALL_OFF, user="user-a")
    assert get(client, user="user-b").get_json()["detection_enabled"] is True


def test_unknown_fields_are_ignored(client):
    response = put(client, {**ALL_OFF, "dark_mode": True})
    assert response.status_code == 200
    assert "dark_mode" not in response.get_json()


@pytest.mark.parametrize(
    "body",
    [
        None,
        [],
        {"audible_alerts": True, "detection_enabled": True},
        {**ALL_OFF, "audible_alerts": "true"},
        {**ALL_OFF, "suggest_reroutes": 1},
        {**ALL_OFF, "detection_enabled": None},
    ],
)
def test_bad_body_is_400_and_saves_nothing(client, body):
    response = client.put(
        "/v1/me/settings",
        data="null" if body is None else None,
        json=body if body is not None else None,
        headers={**auth(), "Content-Type": "application/json"},
    )
    assert response.status_code == 400
    assert get(client).get_json()["detection_enabled"] is True


def test_requires_auth(client):
    assert client.get("/v1/me/settings").status_code == 401
    assert client.put("/v1/me/settings", json=ALL_OFF).status_code == 401
