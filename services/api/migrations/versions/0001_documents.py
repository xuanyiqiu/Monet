"""documents and document_pages

Revision ID: 0001
Revises:
Create Date: 2026-10-11
"""

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision = "0001"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "documents",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("owner", sa.Text, nullable=False),
        sa.Column("title", sa.Text, nullable=False),
        sa.Column("kind", sa.Text, nullable=False),
        sa.Column("status", sa.Text, nullable=False),
        sa.Column("progress", sa.Float, nullable=False, server_default="0"),
        sa.Column("page_count", sa.Integer),
        sa.Column("size_bytes", sa.BigInteger, nullable=False, server_default="0"),
        sa.Column("source_lang", sa.Text, nullable=False),
        sa.Column("target_lang", sa.Text, nullable=False),
        sa.Column("error", sa.Text),
        sa.Column("blob_key", sa.Text, nullable=False),
        sa.Column("mime", sa.Text, nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_index("ix_documents_owner_created", "documents", ["owner", sa.text("created_at DESC")])
    op.create_table(
        "document_pages",
        sa.Column(
            "document_id", postgresql.UUID(as_uuid=True), sa.ForeignKey("documents.id", ondelete="CASCADE")
        ),
        sa.Column("index", sa.Integer, nullable=False),
        sa.Column("extracted", postgresql.JSONB, nullable=False),
        sa.Column("annotated", postgresql.JSONB),
        sa.PrimaryKeyConstraint("document_id", "index"),
    )


def downgrade() -> None:
    op.drop_table("document_pages")
    op.drop_index("ix_documents_owner_created", table_name="documents")
    op.drop_table("documents")
