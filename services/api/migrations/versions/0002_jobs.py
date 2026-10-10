"""durable job queue

Revision ID: 0002
Revises: 0001
Create Date: 2026-10-11
"""

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision = "0002"
down_revision = "0001"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "jobs",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("kind", sa.Text, nullable=False),
        sa.Column("payload", postgresql.JSONB, nullable=False),
        sa.Column("status", sa.Text, nullable=False, server_default="queued"),
        sa.Column("attempts", sa.Integer, nullable=False, server_default="0"),
        sa.Column("max_attempts", sa.Integer, nullable=False, server_default="3"),
        sa.Column("run_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("locked_by", sa.Text),
        sa.Column("locked_until", sa.DateTime(timezone=True)),
        sa.Column("last_error", sa.Text),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_index("ix_jobs_claim", "jobs", ["status", "run_at"])


def downgrade() -> None:
    op.drop_index("ix_jobs_claim", table_name="jobs")
    op.drop_table("jobs")
