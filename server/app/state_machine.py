from __future__ import annotations

from datetime import datetime

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from .models import PauseEvent, ScheduleBlock, WorkSession
from .status import BlockStatus

TERMINAL_STATUSES = {
    BlockStatus.COMPLETED.value,
    BlockStatus.CANCELLED.value,
    BlockStatus.RESCHEDULED.value,
    BlockStatus.SKIPPED.value,
}


class InvalidTransitionError(Exception):
    def __init__(self, message: str):
        self.message = message
        super().__init__(message)


class ActiveSessionConflictError(Exception):
    def __init__(self, active_work_session_id: int):
        self.active_work_session_id = active_work_session_id
        super().__init__(f"Another work session is already active: {active_work_session_id}")


def _open_session(db: Session, block: ScheduleBlock) -> WorkSession | None:
    return db.scalar(
        select(WorkSession)
        .where(
            WorkSession.schedule_block_id == block.id,
            WorkSession.ended_at.is_(None),
        )
    )


def _open_pause(db: Session, session: WorkSession) -> PauseEvent | None:
    return db.scalar(
        select(PauseEvent)
        .where(
            PauseEvent.work_session_id == session.id,
            PauseEvent.ended_at.is_(None),
        )
    )


def start_block(db: Session, block: ScheduleBlock, now: datetime) -> ScheduleBlock:
    if block.status == BlockStatus.ACTIVE.value:
        return block  # idempotent: already started

    if block.status != BlockStatus.PLANNED.value:
        raise InvalidTransitionError(f"Cannot start a block in status '{block.status}'")

    other_open = db.scalar(
        select(WorkSession).where(
            WorkSession.user_id == block.user_id,
            WorkSession.ended_at.is_(None),
        )
    )
    if other_open is not None and other_open.schedule_block_id != block.id:
        raise ActiveSessionConflictError(other_open.id)

    session = WorkSession(
        schedule_block_id=block.id,
        user_id=block.user_id,
        started_at=now,
        status="active",
        created_at=now,
        updated_at=now,
    )
    db.add(session)
    block.status = BlockStatus.ACTIVE.value
    block.updated_at = now

    try:
        db.commit()
    except IntegrityError as exc:
        db.rollback()
        conflict = db.scalar(
            select(WorkSession).where(
                WorkSession.user_id == block.user_id,
                WorkSession.ended_at.is_(None),
            )
        )
        if conflict is not None:
            raise ActiveSessionConflictError(conflict.id) from exc
        raise

    db.refresh(block)
    return block


def pause_block(db: Session, block: ScheduleBlock, now: datetime) -> ScheduleBlock:
    if block.status == BlockStatus.PAUSED.value:
        return block  # idempotent

    if block.status != BlockStatus.ACTIVE.value:
        raise InvalidTransitionError(f"Cannot pause a block in status '{block.status}'")

    session = _open_session(db, block)
    if session is None:
        raise InvalidTransitionError("No open work session for this block")

    if _open_pause(db, session) is None:
        db.add(PauseEvent(work_session_id=session.id, started_at=now, created_at=now))

    session.status = "paused"
    session.updated_at = now
    block.status = BlockStatus.PAUSED.value
    block.updated_at = now
    db.commit()
    db.refresh(block)
    return block


def resume_block(db: Session, block: ScheduleBlock, now: datetime) -> ScheduleBlock:
    if block.status == BlockStatus.ACTIVE.value:
        return block  # idempotent

    if block.status != BlockStatus.PAUSED.value:
        raise InvalidTransitionError(f"Cannot resume a block in status '{block.status}'")

    session = _open_session(db, block)
    if session is None:
        raise InvalidTransitionError("No open work session for this block")

    pause = _open_pause(db, session)
    if pause is not None:
        pause.ended_at = now

    session.status = "active"
    session.updated_at = now
    block.status = BlockStatus.ACTIVE.value
    block.updated_at = now
    db.commit()
    db.refresh(block)
    return block


def complete_block(db: Session, block: ScheduleBlock, now: datetime) -> ScheduleBlock:
    if block.status == BlockStatus.COMPLETED.value:
        return block  # idempotent

    if block.status not in {BlockStatus.ACTIVE.value, BlockStatus.PAUSED.value}:
        raise InvalidTransitionError(f"Cannot complete a block in status '{block.status}'")

    session = _open_session(db, block)
    if session is not None:
        pause = _open_pause(db, session)
        if pause is not None:
            pause.ended_at = now
        session.ended_at = now
        session.status = "completed"
        session.updated_at = now

    block.status = BlockStatus.COMPLETED.value
    block.completed_at = now
    block.updated_at = now
    db.commit()
    db.refresh(block)
    return block


def skip_block(db: Session, block: ScheduleBlock, now: datetime) -> ScheduleBlock:
    if block.status == BlockStatus.SKIPPED.value:
        return block  # idempotent

    if block.status != BlockStatus.PLANNED.value:
        raise InvalidTransitionError(f"Cannot skip a block in status '{block.status}'")

    block.status = BlockStatus.SKIPPED.value
    block.skipped_at = now
    block.updated_at = now
    db.commit()
    db.refresh(block)
    return block


def reschedule_block(
    db: Session,
    block: ScheduleBlock,
    new_start: datetime,
    new_end: datetime,
    now: datetime,
) -> ScheduleBlock:
    if block.status != BlockStatus.PLANNED.value:
        raise InvalidTransitionError(f"Cannot reschedule a block in status '{block.status}'")

    if new_end <= new_start:
        raise InvalidTransitionError("planned_end must be after planned_start")

    new_block = ScheduleBlock(
        user_id=block.user_id,
        project_id=block.project_id,
        block_type=block.block_type,
        title=block.title,
        description=block.description,
        planned_start=new_start,
        planned_end=new_end,
        status=BlockStatus.PLANNED.value,
        created_at=now,
        updated_at=now,
        rescheduled_from_id=block.id,
    )
    db.add(new_block)

    block.status = BlockStatus.RESCHEDULED.value
    block.updated_at = now
    db.commit()
    db.refresh(new_block)
    return new_block


def cancel_block(db: Session, block: ScheduleBlock, now: datetime) -> ScheduleBlock:
    if block.status == BlockStatus.CANCELLED.value:
        return block  # idempotent

    if block.status in TERMINAL_STATUSES:
        raise InvalidTransitionError(f"Cannot cancel a block in status '{block.status}'")

    session = _open_session(db, block)
    if session is not None:
        pause = _open_pause(db, session)
        if pause is not None:
            pause.ended_at = now
        session.ended_at = now
        session.updated_at = now

    block.status = BlockStatus.CANCELLED.value
    block.cancelled_at = now
    block.updated_at = now
    db.commit()
    db.refresh(block)
    return block
