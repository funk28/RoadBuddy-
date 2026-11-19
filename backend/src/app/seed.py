"""`flask --app app seed-hazards`: test potholes around Purdue's campus for the Hazards screen.

Safe to run more than once: a point that already has a live hazard within the match
radius is skipped.
"""

from datetime import timedelta

import click
from flask import Flask, current_app

from app.extensions import db
from app.models import Hazard, utcnow
from app.services.hazards import nearest_live_hazard, point

# (name, lat, lng, severity, sighting_count)
CAMPUS_HAZARDS = [
    ("State St near Grant St", 40.42375, -86.90894, "severe", 3),
    ("Third St by the Union", 40.42465, -86.91120, "moderate", 2),
    ("University St at Stadium Mall", 40.42770, -86.91640, "minor", 1),
    ("Northwestern Ave near Stadium Ave", 40.43153, -86.91640, "severe", 4),
    ("Russell St at Harrison St", 40.42100, -86.91960, "moderate", 1),
    ("Grant St near the parking garage", 40.42640, -86.90900, "minor", 2),
]


def seed_hazards() -> int:
    radius = current_app.config["HAZARD_MATCH_RADIUS_M"]
    now = utcnow()
    added = 0
    for _name, lat, lng, severity, sightings in CAMPUS_HAZARDS:
        if nearest_live_hazard(lat, lng, radius) is not None:
            continue
        db.session.add(
            Hazard(
                location=point(lat, lng),
                lat=lat,
                lng=lng,
                type="pothole",
                severity=severity,
                status="live",
                sighting_count=sightings,
                first_seen=now - timedelta(days=2),
                last_seen=now,
            )
        )
        added += 1
    db.session.commit()
    return added


def register_cli(app: Flask) -> None:
    @app.cli.command("seed-hazards")
    def seed_hazards_command() -> None:
        """Add test potholes around campus."""
        added = seed_hazards()
        click.echo(f"Added {added} hazard(s); {len(CAMPUS_HAZARDS) - added} already existed.")
