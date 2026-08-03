from __future__ import annotations

from datetime import date as date_, datetime, timezone
from typing import Optional

from sqlalchemy import Boolean, Date, ForeignKey, Integer, String, Text, UniqueConstraint
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
    # Archived projects (chapter: archiving) drop out of active pickers but
    # stay resolvable for historical Timeline/Static/Dynamic records that
    # still reference them - never deleted, never hidden from GET /api/projects.
    archived: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    # Freeform Markdown notes (chapter: project/subtask notes) - rendered
    # client-side, stored as raw text server-side.
    notes: Mapped[Optional[str]] = mapped_column(Text, nullable=True)


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


class PlanEntry(Base):
    """A Static Plan entry: the user's original scheduling intent. Immutable
    (see chapter 4.3) - rescheduling never edits start_time/end_time here,
    it appends a PlanChange instead. Dynamic Plan is computed by applying the
    latest PlanChange (if any) on top of this row.
    """

    __tablename__ = "plan_entries"

    id: Mapped[int] = mapped_column(primary_key=True)
    project_id: Mapped[int] = mapped_column(
        ForeignKey("projects.id", ondelete="RESTRICT"), nullable=False, index=True
    )
    start_time: Mapped[datetime] = mapped_column(UTCDateTime, nullable=False, index=True)
    end_time: Mapped[datetime] = mapped_column(UTCDateTime, nullable=False)
    name: Mapped[Optional[str]] = mapped_column(String, nullable=True)
    # Optional link to a checklist item this scheduled block is meant to work on
    # (chapter: planning subtasks) - SET NULL (not CASCADE/RESTRICT) so deleting
    # the subtask un-links it instead of destroying scheduling history.
    subtask_id: Mapped[Optional[int]] = mapped_column(
        ForeignKey("subtasks.id", ondelete="SET NULL"), nullable=True, index=True
    )
    # Set only on entries materialized by a RecurringPlan (chapter: recurring plans).
    # SET NULL (not CASCADE) so deleting the series definition never silently destroys
    # already-materialized Static Plan history - each occurrence is a normal, independently
    # editable/deletable Static entry once created.
    recurring_plan_id: Mapped[Optional[int]] = mapped_column(
        ForeignKey("recurring_plans.id", ondelete="SET NULL"), nullable=True, index=True
    )
    created_at: Mapped[datetime] = mapped_column(
        UTCDateTime,
        default=lambda: datetime.now(timezone.utc),
        nullable=False,
    )


class PlanChange(Base):
    """A change record on top of a PlanEntry - "Move 09:00-11:00 -> 13:00-15:00"
    or a cancellation. The Static entry itself is never edited; the latest
    change (by created_at) for a given plan_entry_id determines the Dynamic
    Plan's view of that entry.
    """

    __tablename__ = "plan_changes"

    id: Mapped[int] = mapped_column(primary_key=True)
    plan_entry_id: Mapped[int] = mapped_column(
        ForeignKey("plan_entries.id", ondelete="CASCADE"), nullable=False, index=True
    )
    change_type: Mapped[str] = mapped_column(String, nullable=False)
    new_start_time: Mapped[Optional[datetime]] = mapped_column(UTCDateTime, nullable=True)
    new_end_time: Mapped[Optional[datetime]] = mapped_column(UTCDateTime, nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        UTCDateTime,
        default=lambda: datetime.now(timezone.utc),
        nullable=False,
        index=True,
    )


class RecurringPlan(Base):
    """A recurring Static Plan template (chapter: recurring plans) - e.g. "Routine at
    09:00-09:30 every weekday". Doesn't itself appear on the calendar; `app.recurrence`
    materializes it into normal PlanEntry rows (linked via `PlanEntry.recurring_plan_id`)
    on a rolling window (see GENERATION_HORIZON_DAYS), same as Google Calendar never
    pre-generates occurrences into infinity.

    Weekday math happens in `timezone` (an IANA name captured from the creating client),
    not UTC - "every day" is a local-calendar concept, and this app has only ever had one
    user/device, so a single stored zone per series is enough (unlike PlanEntry, which
    stores resolved UTC instants once the local time is known).
    """

    __tablename__ = "recurring_plans"

    id: Mapped[int] = mapped_column(primary_key=True)
    project_id: Mapped[int] = mapped_column(
        ForeignKey("projects.id", ondelete="RESTRICT"), nullable=False, index=True
    )
    subtask_id: Mapped[Optional[int]] = mapped_column(
        ForeignKey("subtasks.id", ondelete="SET NULL"), nullable=True, index=True
    )
    name: Mapped[Optional[str]] = mapped_column(String, nullable=True)
    start_time_of_day: Mapped[str] = mapped_column(String, nullable=False)  # "HH:MM"
    end_time_of_day: Mapped[str] = mapped_column(String, nullable=False)  # "HH:MM"
    # Comma-separated ISO weekday numbers (Mon=1..Sun=7), e.g. "1,2,3,4,5" for weekdays.
    weekdays: Mapped[str] = mapped_column(String, nullable=False)
    timezone: Mapped[str] = mapped_column(String, nullable=False)
    series_start_date: Mapped[date_] = mapped_column(Date, nullable=False)
    # None = open-ended, capped only by the rolling generation window - not "forever" in
    # the database, matching the "don't plan into infinity" requirement.
    series_end_date: Mapped[Optional[date_]] = mapped_column(Date, nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        UTCDateTime,
        default=lambda: datetime.now(timezone.utc),
        nullable=False,
    )


class RecurringPlanException(Base):
    """A single date deliberately skipped from a RecurringPlan (chapter: recurring plans,
    "this occurrence only" delete) - generation skips these dates instead of recreating
    the PlanEntry that was just explicitly removed."""

    __tablename__ = "recurring_plan_exceptions"

    id: Mapped[int] = mapped_column(primary_key=True)
    recurring_plan_id: Mapped[int] = mapped_column(
        ForeignKey("recurring_plans.id", ondelete="CASCADE"), nullable=False, index=True
    )
    date: Mapped[date_] = mapped_column(Date, nullable=False)

    __table_args__ = (UniqueConstraint("recurring_plan_id", "date"),)


class Subtask(Base):
    """A checklist item under a Project (chapter: project subtasks) - plain
    text + done flag + a manual `position` for drag-to-reorder. Deleted along
    with its project (ON DELETE CASCADE) - unlike Events/PlanEntries these
    aren't historical records worth preserving once the project is gone.

    Subtasks can nest to unlimited depth via `parent_id` (chapter: nested
    subtasks) - CASCADE (not SET NULL) so deleting a parent removes its
    whole subtree, consistent with subtasks not being historical records."""

    __tablename__ = "subtasks"

    id: Mapped[int] = mapped_column(primary_key=True)
    project_id: Mapped[int] = mapped_column(
        ForeignKey("projects.id", ondelete="CASCADE"), nullable=False, index=True
    )
    parent_id: Mapped[Optional[int]] = mapped_column(
        ForeignKey("subtasks.id", ondelete="CASCADE"), nullable=True, index=True
    )
    title: Mapped[str] = mapped_column(String, nullable=False)
    done: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    position: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    notes: Mapped[Optional[str]] = mapped_column(Text, nullable=True)
    # Marks this subtask as an "instant checklist" container (chapter:
    # checklist entity) - its direct children are flat checkbox items whose
    # `done` toggling fires Instant/start/end calendar Events (see
    # update_subtask). Set only at creation time - not exposed on
    # SubtaskUpdate, retroactively converting an existing subtask isn't
    # supported (delete/recreate instead).
    is_checklist: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    # Meaningful only on a direct child of an is_checklist subtask - the
    # Event currently backing this item's checked state (SET NULL if that
    # Event is ever deleted directly). Null while unchecked, whether never
    # checked or reset via checklist-reset (which clears this WITHOUT
    # deleting the Event - it stays in the calendar as history).
    instant_event_id: Mapped[Optional[int]] = mapped_column(
        ForeignKey("events.id", ondelete="SET NULL"), nullable=True
    )
    created_at: Mapped[datetime] = mapped_column(
        UTCDateTime,
        default=lambda: datetime.now(timezone.utc),
        nullable=False,
    )


class DeviceToken(Base):
    """An FCM registration token for one installed Android client (chapter:
    notifications, server-pushed). A personal single-user app, but modeled as
    a list rather than a singleton so multiple devices work without a schema
    change later."""

    __tablename__ = "device_tokens"

    id: Mapped[int] = mapped_column(primary_key=True)
    token: Mapped[str] = mapped_column(String, nullable=False, unique=True)
    created_at: Mapped[datetime] = mapped_column(
        UTCDateTime,
        default=lambda: datetime.now(timezone.utc),
        nullable=False,
    )


class Reminder(Base):
    """A one-off "remind me at this date+time" note (chapter: special reminders) -
    distinct from PlanEntry/DynamicPlanEntry (which schedule project work) and from
    Event (which records something that happened). `notified` flips to True once the
    scheduler has pushed it - never reset, a fired reminder stays fired."""

    __tablename__ = "reminders"

    id: Mapped[int] = mapped_column(primary_key=True)
    remind_at: Mapped[datetime] = mapped_column(UTCDateTime, nullable=False, index=True)
    message: Mapped[str] = mapped_column(String, nullable=False)
    notified: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    created_at: Mapped[datetime] = mapped_column(
        UTCDateTime,
        default=lambda: datetime.now(timezone.utc),
        nullable=False,
    )


class NotificationState(Base):
    """Dedup state for the server-side push scheduler - one row per kind
    (e.g. "last_start_notified_plan_entry_id"), so the same Dynamic Plan
    entry / active session doesn't trigger a push on every scheduler tick."""

    __tablename__ = "notification_state"

    key: Mapped[str] = mapped_column(String, primary_key=True)
    value: Mapped[int] = mapped_column(Integer, nullable=False)
