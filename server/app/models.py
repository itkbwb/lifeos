from __future__ import annotations

from datetime import datetime, time
from typing import Optional

from sqlalchemy import (
    Boolean,
    ForeignKey,
    Index,
    Integer,
    String,
    Text,
    Time,
    UniqueConstraint,
    text,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship

from .database import Base
from .status import BlockStatus
from .tz import UTCDateTime


class User(Base):
    __tablename__ = "users"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    email: Mapped[Optional[str]] = mapped_column(String(255), unique=True, nullable=True)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime, nullable=False)

    settings: Mapped[Optional["AppSettings"]] = relationship(back_populates="user", uselist=False)
    projects: Mapped[list["Project"]] = relationship(back_populates="user")
    blocks: Mapped[list["ScheduleBlock"]] = relationship(back_populates="user")


class AppSettings(Base):
    __tablename__ = "app_settings"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), unique=True, nullable=False)
    timezone: Mapped[str] = mapped_column(String(64), default="Asia/Seoul", nullable=False)
    locale: Mapped[str] = mapped_column(String(8), default="ru", nullable=False)
    day_start_time: Mapped[time] = mapped_column(Time, default=time(5, 0), nullable=False)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(UTCDateTime, nullable=False)

    user: Mapped[User] = relationship(back_populates="settings")


class Project(Base):
    __tablename__ = "projects"
    __table_args__ = (UniqueConstraint("user_id", "name", name="ux_projects_user_name"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), nullable=False)
    name: Mapped[str] = mapped_column(String(160), nullable=False)
    priority: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    category: Mapped[str] = mapped_column(String(80), default="work", nullable=False)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)

    user: Mapped[User] = relationship(back_populates="projects")
    blocks: Mapped[list["ScheduleBlock"]] = relationship(back_populates="project")


class ScheduleBlock(Base):
    __tablename__ = "schedule_blocks"
    __table_args__ = (Index("ix_schedule_blocks_user_planned_start", "user_id", "planned_start"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), nullable=False)
    project_id: Mapped[Optional[int]] = mapped_column(ForeignKey("projects.id"), nullable=True)

    block_type: Mapped[str] = mapped_column(String(80), default="work", nullable=False)
    title: Mapped[str] = mapped_column(String(180), nullable=False)
    description: Mapped[Optional[str]] = mapped_column(Text, nullable=True)

    planned_start: Mapped[datetime] = mapped_column(UTCDateTime, nullable=False)
    planned_end: Mapped[datetime] = mapped_column(UTCDateTime, nullable=False)

    status: Mapped[str] = mapped_column(String(20), default=BlockStatus.PLANNED.value, nullable=False)

    created_at: Mapped[datetime] = mapped_column(UTCDateTime, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(UTCDateTime, nullable=False)
    completed_at: Mapped[Optional[datetime]] = mapped_column(UTCDateTime, nullable=True)
    skipped_at: Mapped[Optional[datetime]] = mapped_column(UTCDateTime, nullable=True)
    cancelled_at: Mapped[Optional[datetime]] = mapped_column(UTCDateTime, nullable=True)

    rescheduled_from_id: Mapped[Optional[int]] = mapped_column(
        ForeignKey("schedule_blocks.id"), nullable=True
    )

    user: Mapped[User] = relationship(back_populates="blocks")
    project: Mapped[Optional[Project]] = relationship(back_populates="blocks")
    work_sessions: Mapped[list["WorkSession"]] = relationship(back_populates="block")
    task_items: Mapped[list["TaskItem"]] = relationship(back_populates="block", order_by="TaskItem.position")
    rescheduled_from: Mapped[Optional["ScheduleBlock"]] = relationship(
        remote_side=[id], foreign_keys=[rescheduled_from_id]
    )


class WorkSession(Base):
    __tablename__ = "work_sessions"
    __table_args__ = (
        Index(
            "ux_work_sessions_one_active_per_user",
            "user_id",
            unique=True,
            sqlite_where=text("status = 'active' AND ended_at IS NULL"),
        ),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    schedule_block_id: Mapped[int] = mapped_column(ForeignKey("schedule_blocks.id"), nullable=False)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), nullable=False)

    started_at: Mapped[datetime] = mapped_column(UTCDateTime, nullable=False)
    ended_at: Mapped[Optional[datetime]] = mapped_column(UTCDateTime, nullable=True)
    status: Mapped[str] = mapped_column(String(20), default="active", nullable=False)

    created_at: Mapped[datetime] = mapped_column(UTCDateTime, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(UTCDateTime, nullable=False)

    block: Mapped[ScheduleBlock] = relationship(back_populates="work_sessions")
    pause_events: Mapped[list["PauseEvent"]] = relationship(back_populates="work_session")


class PauseEvent(Base):
    __tablename__ = "pause_events"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    work_session_id: Mapped[int] = mapped_column(ForeignKey("work_sessions.id"), nullable=False)

    started_at: Mapped[datetime] = mapped_column(UTCDateTime, nullable=False)
    ended_at: Mapped[Optional[datetime]] = mapped_column(UTCDateTime, nullable=True)
    reason: Mapped[Optional[str]] = mapped_column(String(200), nullable=True)

    created_at: Mapped[datetime] = mapped_column(UTCDateTime, nullable=False)

    work_session: Mapped[WorkSession] = relationship(back_populates="pause_events")


class TaskItem(Base):
    __tablename__ = "task_items"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    schedule_block_id: Mapped[int] = mapped_column(ForeignKey("schedule_blocks.id"), nullable=False)

    title: Mapped[str] = mapped_column(String(200), nullable=False)
    position: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    is_completed: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    completed_at: Mapped[Optional[datetime]] = mapped_column(UTCDateTime, nullable=True)

    block: Mapped[ScheduleBlock] = relationship(back_populates="task_items")
