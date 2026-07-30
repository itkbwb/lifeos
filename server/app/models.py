from __future__ import annotations

from datetime import datetime, timezone
from typing import Optional

from sqlalchemy import ForeignKey, String
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base, UTCDateTime


class Project(Base):
    __tablename__ = "projects"

    id: Mapped[int] = mapped_column(primary_key=True)
    name: Mapped[str] = mapped_column(String, nullable=False)
    color: Mapped[str] = mapped_column(String, nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        UTCDateTime,
        default=lambda: datetime.now(timezone.utc),
        nullable=False,
    )


class Event(Base):
    __tablename__ = "events"

    id: Mapped[int] = mapped_column(primary_key=True)
    project_id: Mapped[int] = mapped_column(
        ForeignKey("projects.id", ondelete="RESTRICT"), nullable=False, index=True
    )
    type: Mapped[str] = mapped_column(String, nullable=False)
    occurred_at: Mapped[datetime] = mapped_column(UTCDateTime, nullable=False, index=True)
    label: Mapped[Optional[str]] = mapped_column(String, nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        UTCDateTime,
        default=lambda: datetime.now(timezone.utc),
        nullable=False,
    )

    # Correction / audit trail (see 3.7: events are immutable historical facts;
    # a correction inserts a new row and only ever flips these two columns on
    # the original, never occurred_at/type/project_id/label).
    superseded_by_id: Mapped[Optional[int]] = mapped_column(
        ForeignKey("events.id", ondelete="SET NULL"), nullable=True, unique=True
    )
    corrects_id: Mapped[Optional[int]] = mapped_column(
        ForeignKey("events.id", ondelete="SET NULL"), nullable=True, unique=True
    )
    corrected_at: Mapped[Optional[datetime]] = mapped_column(UTCDateTime, nullable=True)
