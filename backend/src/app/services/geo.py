"""Distance helpers for duplicate hazard checks.

PostGIS geography is the source of truth in production. These functions mirror
that check for the in-memory repository and for unit tests. The match rule
allows a 1 cm tolerance so a point that lands on the radius boundary still
counts as inside after floating-point rounding.
"""

from math import asin, atan2, cos, degrees, radians, sin, sqrt

EARTH_RADIUS_M = 6_371_000.0
RADIUS_TOLERANCE_M = 0.01


def haversine_m(lat1: float, lng1: float, lat2: float, lng2: float) -> float:
    phi1 = radians(lat1)
    phi2 = radians(lat2)
    d_phi = radians(lat2 - lat1)
    d_lng = radians(lng2 - lng1)
    a = sin(d_phi / 2) ** 2 + cos(phi1) * cos(phi2) * sin(d_lng / 2) ** 2
    return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))


def offset_m(lat: float, lng: float, distance_m: float, bearing_deg: float = 0.0) -> tuple[float, float]:
    bearing = radians(bearing_deg)
    lat1 = radians(lat)
    lng1 = radians(lng)
    angular = distance_m / EARTH_RADIUS_M
    lat2 = asin(sin(lat1) * cos(angular) + cos(lat1) * sin(angular) * cos(bearing))
    lng2 = lng1 + atan2(
        sin(bearing) * sin(angular) * cos(lat1),
        cos(angular) - sin(lat1) * sin(lat2),
    )
    return degrees(lat2), degrees(lng2)


def within_radius(distance_m: float, radius_m: float) -> bool:
    return distance_m <= radius_m + RADIUS_TOLERANCE_M
