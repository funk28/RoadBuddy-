"""Google ID token auth: every /v1 request sends `Authorization: Bearer <token>`."""

from collections.abc import Callable
from dataclasses import dataclass
from typing import Any

from flask import current_app, g, request
from google.auth.transport import requests as google_requests
from google.oauth2 import id_token
from sqlalchemy.exc import IntegrityError

from app.errors import ApiError
from app.extensions import db
from app.models import User

GOOGLE_ISSUERS = {"accounts.google.com", "https://accounts.google.com"}
DEV_PREFIX = "dev:"


@dataclass(frozen=True)
class Identity:
    subject: str
    email: str | None = None


TokenVerifier = Callable[[str], Identity]


def _unauthorized(message: str) -> ApiError:
    return ApiError(401, "unauthorized", message)


def google_verifier(client_ids: list[str]) -> TokenVerifier:
    transport = google_requests.Request()

    def verify(token: str) -> Identity:
        if not client_ids:
            raise _unauthorized("Server has no GOOGLE_CLIENT_IDS configured.")
        try:
            # audience=None skips the library's check; we check against every client ID below.
            claims: dict[str, Any] = id_token.verify_oauth2_token(token, transport, audience=None)
        except ValueError as exc:
            raise _unauthorized("Invalid Google ID token.") from exc
        if claims.get("iss") not in GOOGLE_ISSUERS or claims.get("aud") not in client_ids:
            raise _unauthorized("Google ID token was not issued for this app.")
        return Identity(subject=str(claims["sub"]), email=claims.get("email"))

    return verify


def current_user() -> User:
    return g.current_user


def authenticate() -> None:
    """before_request hook for the /v1 blueprint: sets g.current_user or raises 401."""
    header = request.headers.get("Authorization", "")
    scheme, _, token = header.partition(" ")
    if scheme.lower() != "bearer" or not token.strip():
        raise _unauthorized("Missing bearer token.")
    token = token.strip()

    if current_app.config["DEV_AUTH"] and token.startswith(DEV_PREFIX):
        identity = Identity(subject=token, email=None)
    else:
        verifier: TokenVerifier = current_app.extensions["token_verifier"]
        identity = verifier(token)

    g.current_user = _get_or_create_user(identity)


def _get_or_create_user(identity: Identity) -> User:
    user = db.session.execute(
        db.select(User).filter_by(google_sub=identity.subject)
    ).scalar_one_or_none()
    if user is not None:
        return user
    user = User(google_sub=identity.subject, email=identity.email)
    db.session.add(user)
    try:
        db.session.commit()
    except IntegrityError:
        # Two first requests from the same new user raced; use the row that won.
        db.session.rollback()
        user = db.session.execute(
            db.select(User).filter_by(google_sub=identity.subject)
        ).scalar_one()
    return user
