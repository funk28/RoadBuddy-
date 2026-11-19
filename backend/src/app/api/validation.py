"""Small validators that raise 400s in the contract's error format."""

import math
from datetime import datetime
from typing import Any

from app.errors import bad_request


def number(
    value: Any,
    field: str,
    *,
    minimum: float | None = None,
    maximum: float | None = None,
    exclusive_maximum: float | None = None,
) -> float:
    # bool is a subclass of int in Python, but true/false aren't numbers in the contract.
    if isinstance(value, bool) or not isinstance(value, int | float):
        raise bad_request(f"{field} must be a number.")
    result = float(value)
    if not math.isfinite(result):
        raise bad_request(f"{field} must be finite.")
    if minimum is not None and result < minimum:
        raise bad_request(f"{field} must be at least {minimum:g}.")
    if maximum is not None and result > maximum:
        raise bad_request(f"{field} must be at most {maximum:g}.")
    if exclusive_maximum is not None and result >= exclusive_maximum:
        raise bad_request(f"{field} must be less than {exclusive_maximum:g}.")
    return result


def location(value: Any, field: str = "location") -> tuple[float, float]:
    if not isinstance(value, dict):
        raise bad_request(f"{field} must be an object with lat and lng.")
    if "lat" not in value or "lng" not in value:
        raise bad_request(f"{field} needs both lat and lng.")
    lat = number(value["lat"], f"{field}.lat", minimum=-90, maximum=90)
    lng = number(value["lng"], f"{field}.lng", minimum=-180, maximum=180)
    return lat, lng


def timestamp(value: Any, field: str) -> datetime:
    if not isinstance(value, str):
        raise bad_request(f"{field} must be an ISO-8601 date-time string.")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise bad_request(f"{field} must be an ISO-8601 date-time.") from exc
    if parsed.tzinfo is None:
        raise bad_request(f"{field} must include a UTC offset, e.g. 2026-11-13T14:48:02Z.")
    return parsed


def boolean(value: Any, field: str) -> bool:
    if not isinstance(value, bool):
        raise bad_request(f"{field} must be true or false.")
    return value
