"""Add detections.trip_id for the My reports "This trip" filter.

Revision ID: 0002
Revises: 0001
Create Date: 2026-11-18
"""

import sqlalchemy as sa
from alembic import op

revision = "0002"
down_revision = "0001"
branch_labels = None
depends_on = None


def upgrade():
    op.add_column("detections", sa.Column("trip_id", sa.String(64), nullable=True))


def downgrade():
    op.drop_column("detections", "trip_id")
