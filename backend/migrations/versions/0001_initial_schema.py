"""Initial schema: users, hazards, detections, user_settings.

Revision ID: 0001
Revises:
Create Date: 2026-11-18
"""

import sqlalchemy as sa
from alembic import op
from geoalchemy2 import Geography

revision = "0001"
down_revision = None
branch_labels = None
depends_on = None


def upgrade():
    op.execute("CREATE EXTENSION IF NOT EXISTS postgis")

    op.create_table(
        "users",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("google_sub", sa.String(255), nullable=False, unique=True),
        sa.Column("email", sa.String(320), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
    )

    op.create_table(
        "hazards",
        sa.Column("id", sa.Integer(), primary_key=True),
        # The GiST index is created explicitly below.
        sa.Column(
            "location",
            Geography(geometry_type="POINT", srid=4326, spatial_index=False),
            nullable=False,
        ),
        sa.Column("type", sa.String(32), nullable=False),
        sa.Column("severity", sa.String(16), nullable=False),
        sa.Column("status", sa.String(16), nullable=False),
        sa.Column("sighting_count", sa.Integer(), nullable=False),
        sa.Column("first_seen", sa.DateTime(timezone=True), nullable=False),
        sa.Column("last_seen", sa.DateTime(timezone=True), nullable=False),
        sa.Column("lat", sa.Float(), nullable=False),
        sa.Column("lng", sa.Float(), nullable=False),
        sa.CheckConstraint(
            "severity IN ('minor', 'moderate', 'severe')", name="ck_hazards_severity"
        ),
        sa.CheckConstraint("status IN ('live', 'expired', 'removed')", name="ck_hazards_status"),
        sa.CheckConstraint("sighting_count >= 0", name="ck_hazards_sighting_count"),
    )
    op.create_index("idx_hazards_location", "hazards", ["location"], postgresql_using="gist")

    op.create_table(
        "detections",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("client_detection_id", sa.String(128), nullable=False, unique=True),
        sa.Column("user_id", sa.Integer(), sa.ForeignKey("users.id"), nullable=False),
        sa.Column("hazard_id", sa.Integer(), sa.ForeignKey("hazards.id"), nullable=True),
        sa.Column("outcome", sa.String(16), nullable=False),
        sa.Column("captured_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("received_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("lat", sa.Float(), nullable=False),
        sa.Column("lng", sa.Float(), nullable=False),
        sa.Column("heading_deg", sa.Float(), nullable=True),
        sa.Column("local_confidence", sa.Float(), nullable=True),
        sa.Column("image_key", sa.String(255), nullable=False),
        sa.Column("is_hazard", sa.Boolean(), nullable=False),
        sa.Column("hazard_type", sa.String(32), nullable=True),
        sa.Column("severity", sa.String(16), nullable=True),
        sa.Column("classifier_confidence", sa.Float(), nullable=False),
        sa.Column("model_id", sa.String(64), nullable=False),
        sa.Column("removed_at", sa.DateTime(timezone=True), nullable=True),
        sa.CheckConstraint(
            "outcome IN ('created', 'matched', 'rejected')", name="ck_detections_outcome"
        ),
    )
    op.create_index("ix_detections_user_id", "detections", ["user_id"])
    op.create_index("ix_detections_hazard_id", "detections", ["hazard_id"])

    op.create_table(
        "user_settings",
        sa.Column("user_id", sa.Integer(), sa.ForeignKey("users.id"), primary_key=True),
        sa.Column("audible_alerts", sa.Boolean(), nullable=False),
        sa.Column("detection_enabled", sa.Boolean(), nullable=False),
        sa.Column("suggest_reroutes", sa.Boolean(), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
    )


def downgrade():
    op.drop_table("user_settings")
    op.drop_table("detections")
    op.drop_table("hazards")
    op.drop_table("users")
