from datetime import datetime, timedelta, timezone

from app.db.memory import MemoryRepository
from app.services.geo import offset_m
from app.services.hazards import HazardService, IncomingReport, find_duplicate

WHEN = datetime(2026, 11, 13, 14, 0, tzinfo=timezone.utc)
TTL = timedelta(hours=24)


def service(repo, radius_m=25):
    return HazardService(repo, radius_m=radius_m, ttl=TTL)


def report(client_id, lat, lng, *, when=WHEN, hazard_type="pothole", severity="severe", is_hazard=True):
    return IncomingReport(
        client_detection_id=client_id,
        captured_at=when,
        lat=lat,
        lng=lng,
        is_hazard=is_hazard,
        hazard_type=hazard_type,
        severity=severity,
    )


def test_first_report_creates_one_live_hazard():
    repo = MemoryRepository()
    result = service(repo).report(report("a", 40.4237, -86.9212))

    assert result.outcome == "created"
    assert result.hazard.sighting_count == 1
    assert result.hazard.status == "live"
    assert result.hazard.expires_at == WHEN + TTL
    assert len(repo.list_hazards()) == 1
    assert repo.detection_count() == 1


def test_same_type_within_10m_matches_existing_hazard():
    repo = MemoryRepository()
    created = service(repo).report(report("a", 40.4237, -86.9212))
    lat, lng = offset_m(40.4237, -86.9212, 10)
    matched = service(repo).report(report("b", lat, lng, when=WHEN + timedelta(hours=1)))

    assert matched.outcome == "matched"
    assert matched.hazard.id == created.hazard.id
    assert matched.hazard.sighting_count == 2
    assert matched.hazard.lat == created.hazard.lat
    assert matched.hazard.lng == created.hazard.lng
    assert len(repo.list_hazards()) == 1
    assert repo.detection_count() == 2


def test_same_type_at_radius_boundary_matches():
    repo = MemoryRepository()
    created = service(repo).report(report("a", 40.0, -86.0))
    lat, lng = offset_m(40.0, -86.0, 25)
    matched = service(repo).report(report("b", lat, lng))

    assert matched.outcome == "matched"
    assert matched.hazard.id == created.hazard.id


def test_same_type_just_outside_radius_creates_a_second_hazard():
    repo = MemoryRepository()
    service(repo).report(report("a", 40.0, -86.0))
    lat, lng = offset_m(40.0, -86.0, 26)
    created = service(repo).report(report("b", lat, lng))

    assert created.outcome == "created"
    assert len(repo.list_hazards()) == 2


def test_expired_and_removed_hazards_do_not_match():
    for index, status in enumerate(("expired", "removed")):
        repo = MemoryRepository()
        hazards = service(repo)
        origin_lat = 40.0 + index
        existing = hazards.report(report(f"seed-{status}", origin_lat, -86.0))
        repo.set_status(existing.hazard.id, status)
        lat, lng = offset_m(origin_lat, -86.0, 5)
        result = hazards.report(report(f"new-{status}", lat, lng))
        assert result.outcome == "created"
        assert result.hazard.id != existing.hazard.id
        assert len(repo.list_hazards()) == 2


def test_different_hazard_type_within_5m_is_not_a_duplicate():
    repo = MemoryRepository()
    hazards = service(repo)
    hazards.report(report("a", 40.0, -86.0, hazard_type="pothole"))
    lat, lng = offset_m(40.0, -86.0, 5)
    debris = hazards.report(report("b", lat, lng, hazard_type="debris"))

    assert debris.outcome == "created"
    assert {hazard.hazard_type for hazard in repo.list_hazards()} == {"pothole", "debris"}


def test_radius_is_configurable():
    lat, lng = offset_m(40.0, -86.0, 10)

    tight = MemoryRepository()
    service(tight, radius_m=5).report(report("a", 40.0, -86.0))
    assert service(tight, radius_m=5).report(report("b", lat, lng)).outcome == "created"

    wide = MemoryRepository()
    service(wide, radius_m=50).report(report("a", 40.0, -86.0))
    assert service(wide, radius_m=50).report(report("b", lat, lng)).outcome == "matched"


def test_nearest_live_hazard_wins_over_a_farther_newer_one():
    repo = MemoryRepository()
    near_lat, near_lng = offset_m(40.0, -86.0, 8)
    far_lat, far_lng = offset_m(40.0, -86.0, 20, bearing_deg=90)
    near = repo.seed_hazard(
        hazard_type="pothole",
        severity="minor",
        status="live",
        lat=near_lat,
        lng=near_lng,
        sighting_count=1,
        last_confirmed_at=WHEN - timedelta(days=2),
        expires_at=WHEN + TTL,
    )
    repo.seed_hazard(
        hazard_type="pothole",
        severity="severe",
        status="live",
        lat=far_lat,
        lng=far_lng,
        sighting_count=4,
        last_confirmed_at=WHEN,
        expires_at=WHEN + TTL,
    )

    matched = service(repo).report(report("new", 40.0, -86.0, severity="severe"))

    assert matched.hazard.id == near.id
    assert matched.hazard.sighting_count == 2


def test_equal_distance_keeps_the_most_recently_confirmed_hazard():
    repo = MemoryRepository()
    older = repo.seed_hazard(
        hazard_type="pothole",
        severity="moderate",
        status="live",
        lat=40.0,
        lng=-86.0,
        sighting_count=1,
        last_confirmed_at=WHEN - timedelta(hours=5),
        expires_at=WHEN + TTL,
    )
    newer = repo.seed_hazard(
        hazard_type="pothole",
        severity="moderate",
        status="live",
        lat=40.0,
        lng=-86.0,
        sighting_count=3,
        last_confirmed_at=WHEN - timedelta(hours=1),
        expires_at=WHEN + TTL,
    )

    matched = find_duplicate(
        [older, newer],
        hazard_type="pothole",
        lat=40.0,
        lng=-86.0,
        radius_m=25,
    )

    assert matched.id == newer.id


def test_match_extends_expiry_and_keeps_severity():
    repo = MemoryRepository()
    hazards = service(repo)
    created = hazards.report(report("a", 40.0, -86.0, severity="moderate"))
    later = WHEN + timedelta(hours=20)
    lat, lng = offset_m(40.0, -86.0, 4)
    matched = hazards.report(report("b", lat, lng, when=later, severity="severe"))

    assert matched.hazard.severity == "moderate"
    assert matched.hazard.severity == created.hazard.severity
    assert matched.hazard.last_confirmed_at == later
    assert matched.hazard.expires_at == later + TTL


def test_retry_with_the_same_client_detection_id_does_not_add_a_sighting():
    repo = MemoryRepository()
    hazards = service(repo)
    first = hazards.report(report("same", 40.0, -86.0))
    lat, lng = offset_m(40.0, -86.0, 3)
    retry = hazards.report(report("same", lat, lng, when=WHEN + timedelta(minutes=5)))

    assert retry.outcome == "created"
    assert retry.detection.id == first.detection.id
    assert retry.hazard.sighting_count == 1
    assert repo.detection_count() == 1
    assert len(repo.list_hazards()) == 1


def test_rejected_frame_stores_nothing():
    repo = MemoryRepository()
    result = service(repo).report(report("a", 40.0, -86.0, is_hazard=False, hazard_type=None, severity=None))

    assert result.outcome == "rejected"
    assert result.detection is None
    assert result.hazard is None
    assert repo.list_hazards() == []
    assert repo.detection_count() == 0
