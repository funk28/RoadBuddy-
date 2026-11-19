"""The contract's error format: {"error": {"code": "...", "message": "..."}}."""

from flask import Flask, jsonify
from werkzeug.exceptions import HTTPException


class ApiError(Exception):
    def __init__(self, status: int, code: str, message: str) -> None:
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message


def bad_request(message: str, code: str = "invalid_request") -> ApiError:
    return ApiError(400, code, message)


def not_found(message: str = "Not found.") -> ApiError:
    return ApiError(404, "not_found", message)


def _body(code: str, message: str) -> dict:
    return {"error": {"code": code, "message": message}}


def register_error_handlers(app: Flask) -> None:
    @app.errorhandler(ApiError)
    def handle_api_error(err: ApiError):
        return jsonify(_body(err.code, err.message)), err.status

    @app.errorhandler(HTTPException)
    def handle_http_error(err: HTTPException):
        code = (err.name or "error").lower().replace(" ", "_")
        return jsonify(_body(code, err.description or "")), err.code or 500

    @app.errorhandler(Exception)
    def handle_unexpected(err: Exception):
        app.logger.exception("Unhandled error")
        return jsonify(_body("internal_error", "Something went wrong.")), 500
