from datetime import date, datetime, time
from typing import Optional

from sqlalchemy import Boolean, Date, DateTime, ForeignKey, Integer, String, Text, Time
from sqlalchemy.orm import Mapped, mapped_column, relationship

from .database import Base


class Project(Base):
    __tablename__ = "projects"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    name: Mapped[str] = mapped_column(String(160), unique=True, nullable=False)
    priority: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    category: Mapped[str] = mapped_column(String(80), default="work", nullable=False)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)

    blocks: Mapped[list["ScheduleBlock"]] = relationship(back_populates="project")


class ScheduleBlock(Base):
    __tablename__ = "schedule_blocks"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    block_date: Mapped[date] = mapped_column(Date, index=True, nullable=False)
    start_time: Mapped[time] = mapped_column(Time, nullable=False)
    end_time: Mapped[time] = mapped_column(Time, nullable=False)
    title: Mapped[str] = mapped_column(String(180), nullable=False)
    notes: Mapped[Optional[str]] = mapped_column(Text, nullable=True)
    block_type: Mapped[str] = mapped_column(String(80), default="work", nullable=False)
    status: Mapped[str] = mapped_column(String(40), default="planned", nullable=False)

    project_id: Mapped[Optional[int]] = mapped_column(
        ForeignKey("projects.id"),
        nullable=True,
    )
    project: Mapped[Optional[Project]] = relationship(back_populates="blocks")


class RoutineItem(Base):
    __tablename__ = "routine_items"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    routine_name: Mapped[str] = mapped_column(String(80), index=True, nullable=False)
    title: Mapped[str] = mapped_column(String(180), nullable=False)
    position: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)


class CompletionLog(Base):
    __tablename__ = "completion_logs"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    block_id: Mapped[Optional[int]] = mapped_column(
        ForeignKey("schedule_blocks.id"),
        nullable=True,
    )
    action: Mapped[str] = mapped_column(String(40), nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime,
        default=datetime.utcnow,
        nullable=False,
    )
