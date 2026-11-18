from conftest import auth

from app import create_app


def test_missing_token_is_401_in_contract_error_format(client):
    response = client.delete("/v1/detections/d_1")
    assert response.status_code == 401
    assert response.get_json() == {
        "error": {"code": "unauthorized", "message": "Missing bearer token."}
    }


def test_invalid_token_is_401(client):
    response = client.delete("/v1/detections/d_1", headers=auth("not-a-real-token"))
    assert response.status_code == 401
    assert response.get_json()["error"]["code"] == "unauthorized"


def test_dev_token_rejected_unless_dev_auth_enabled(client):
    response = client.delete("/v1/detections/d_1", headers={"Authorization": "Bearer dev:me"})
    assert response.status_code == 401


def test_dev_token_accepted_when_dev_auth_enabled(app):
    app.config["DEV_AUTH"] = True
    response = app.test_client().delete(
        "/v1/detections/d_1", headers={"Authorization": "Bearer dev:me"}
    )
    # Authenticated, so the request gets as far as looking up the detection.
    assert response.status_code == 404


def test_google_verifier_refuses_when_no_client_ids_configured():
    app = create_app({"GOOGLE_CLIENT_IDS": [], "TESTING": True})
    verifier = app.extensions["token_verifier"]
    try:
        verifier("anything")
    except Exception as exc:  # ApiError
        assert getattr(exc, "status", None) == 401
    else:
        raise AssertionError("expected a 401")


def test_unknown_v1_route_is_json_404(client):
    response = client.get("/v1/nope", headers=auth())
    assert response.status_code == 404
    assert response.get_json()["error"]["code"] == "not_found"
