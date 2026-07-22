from __future__ import annotations

from datetime import datetime, timedelta

from sqlalchemy import select
from sqlalchemy.orm import Session

from .models import PauseEvent, Project, ScheduleBlock, WorkSession
from .state import effective_status, is_overdue


def _duration_minutes(start: datetime, end: datetime) -> int:
    return max(0, int((end - start).total_seconds() // 60))


def find_open_session(db: Session, block: ScheduleBlock) -> WorkSession | None:
    return db.scalar(
        select(WorkSession).where(
            WorkSession.schedule_block_id == block.id,
            WorkSession.ended_at.is_(None),
        )
    )


def paused_seconds(db: Session, session: WorkSession, now: datetime) -> int:
    total = timedelta()
    pauses = db.scalars(
        select(PauseEvent).where(PauseEvent.work_session_id == session.id)
    )
    for pause in pauses:
        end = pause.ended_at or now
        total += end - pause.started_at
    return max(0, int(total.total_seconds()))


def elapsed_seconds(session: WorkSession, now: datetime, paused: int) -> int:
    end = session.ended_at or now
    raw = (end - session.started_at).total_seconds()
    return max(0, int(raw) - paused)


def serialize_block(db: Session, block: ScheduleBlock, now: datetime) -> dict:
    effective = effective_status(block, now)
    return {
        "id": block.id,
        "project_id": block.project_id,
        "project_name": block.project.name if block.project else None,
        "block_type": block.block_type,
        "title": block.title,
        "description": block.description,
        "planned_start": block.planned_start.isoformat(),
        "planned_end": block.planned_end.isoformat(),
        "planned_duration_minutes": _duration_minutes(block.planned_start, block.planned_end),
        "stored_status": block.status,
        "status": effective,
        "overdue": is_overdue(block, effective, now),
        "created_at": block.created_at.isoformat(),
        "updated_at": block.updated_at.isoformat(),
        "completed_at": block.completed_at.isoformat() if block.completed_at else None,
        "skipped_at": block.skipped_at.isoformat() if block.skipped_at else None,
        "cancelled_at": block.cancelled_at.isoformat() if block.cancelled_at else None,
        "rescheduled_from_id": block.rescheduled_from_id,
    }


def serialize_work_session(db: Session, session: WorkSession, now: datetime) -> dict:
    paused = paused_seconds(db, session, now)
    return {
        "id": session.id,
        "schedule_block_id": session.schedule_block_id,
        "started_at": session.started_at.isoformat(),
        "ended_at": session.ended_at.isoformat() if session.ended_at else None,
        "status": session.status,
        "elapsed_seconds": elapsed_seconds(session, now, paused),
        "paused_seconds": paused,
    }


def serialize_project_stat(project: Project, blocks: list[ScheduleBlock]) -> dict:
    scheduled = sum(_duration_minutes(b.planned_start, b.planned_end) for b in blocks)
    completed = sum(
        _duration_minutes(b.planned_start, b.planned_end)
        for b in blocks
        if b.status == "completed"
    )
    return {
        "id": project.id,
        "name": project.name,
        "priority": project.priority,
        "category": project.category,
        "scheduled_minutes": scheduled,
        "completed_minutes": completed,
        "block_count": len(blocks),
    }
