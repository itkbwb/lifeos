from __future__ import annotations

from datetime import datetime, timedelta

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from .models import PauseEvent, ScheduleBlock, WorkSession
from .serializers import elapsed_seconds, paused_seconds
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


def _active_session(db: Session, user_id: int) -> WorkSession | None:
    return db.scalar(
        select(WorkSession).where(
            WorkSession.user_id == user_id,
            WorkSession.status == "active",
            WorkSession.ended_at.is_(None),
        )
    )


def start_block(db: Session, block: ScheduleBlock, now: datetime) -> ScheduleBlock:
    if block.status == BlockStatus.ACTIVE.value:
        return block  # idempotent: already started

    if block.status != BlockStatus.PLANNED.value:
        raise InvalidTransitionError(f"Cannot start a block in status '{block.status}'")

    # The plan is never mutated by starting a block - what actually happened
    # (this WorkSession's real start/end) is a separate "fact" layer that the
    # serializer overlays on top of the unchanged plan. See pause_block() for
    # what happens when a block gets started outside its own planned window.

    # Switching tasks: starting a new block while another is active pauses
    # the other one rather than erroring - the user explicitly chose to
    # switch, this isn't a race. Only a genuine double-tap race (two starts
    # for two different blocks landing in the same instant) falls through
    # to the IntegrityError/409 path below.
    other_active = _active_session(db, block.user_id)
    if other_active is not None and other_active.schedule_block_id != block.id:
        other_block = db.get(ScheduleBlock, other_active.schedule_block_id)
        if other_block is not None:
            pause_block(db, other_block, now)

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
        conflict = _active_session(db, block.user_id)
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

    # Opportunistic work: this session was started outside the block's own
    # planned window (e.g. worked on tomorrow's task today) and is being
    # deferred, not finished. Split it: the worked time becomes its own
    # standalone record at the real time it happened (so it stays visible
    # once the session's fact-time display would otherwise fully replace the
    # original block's plan-time display), and the remainder of the original
    # plan shrinks by however much was actually worked.
    if not (block.planned_start <= session.started_at < block.planned_end):
        worked = elapsed_seconds(session, now, paused_seconds(db, session, now))
        original_duration = (block.planned_end - block.planned_start).total_seconds()

        fact_block = ScheduleBlock(
            user_id=block.user_id,
            project_id=block.project_id,
            block_type=block.block_type,
            title=block.title,
            description=block.description,
            planned_start=session.started_at,
            planned_end=now,
            status=BlockStatus.PAUSED.value,
            created_at=now,
            updated_at=now,
            rescheduled_from_id=block.id,
        )
        db.add(fact_block)
        db.flush()
        session.schedule_block_id = fact_block.id

        if worked >= original_duration:
            block.status = BlockStatus.CANCELLED.value
            block.cancelled_at = now
        else:
            block.planned_end = block.planned_start + timedelta(seconds=original_duration - worked)
            block.status = BlockStatus.PLANNED.value

        block.updated_at = now
        db.commit()
        db.refresh(block)
        return block

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


def reopen_block(db: Session, block: ScheduleBlock, now: datetime) -> ScheduleBlock:
    """Undoes a completed/skipped/cancelled mark that shouldn't have
    happened (mis-tap, stale test data, etc.), returning the block to plain
    "planned" so it can be started normally again. Doesn't touch the old
    WorkSession - starting again creates a fresh one, the old one just stops
    counting toward "actual" totals once a new session supersedes it."""
    if block.status == BlockStatus.PLANNED.value:
        return block  # idempotent

    if block.status not in {
        BlockStatus.COMPLETED.value,
        BlockStatus.SKIPPED.value,
        BlockStatus.CANCELLED.value,
    }:
        raise InvalidTransitionError(f"Cannot reopen a block in status '{block.status}'")

    block.status = BlockStatus.PLANNED.value
    block.completed_at = None
    block.skipped_at = None
    block.cancelled_at = None
    block.updated_at = now
    db.commit()
    db.refresh(block)
    return block


def restart_block(db: Session, block: ScheduleBlock, now: datetime) -> ScheduleBlock:
    """Zeroes the elapsed timer and keeps working on the same block: the
    current WorkSession is closed out (discarded from future actual-time
    totals, which always look at the most recent session per block) and a
    fresh one starts immediately."""
    if block.status not in {BlockStatus.ACTIVE.value, BlockStatus.PAUSED.value}:
        raise InvalidTransitionError(f"Cannot restart a block in status '{block.status}'")

    old_session = _open_session(db, block)
    if old_session is not None:
        pause = _open_pause(db, old_session)
        if pause is not None:
            pause.ended_at = now
        old_session.ended_at = now
        old_session.status = "restarted"
        old_session.updated_at = now

    new_session = WorkSession(
        schedule_block_id=block.id,
        user_id=block.user_id,
        started_at=now,
        status="active",
        created_at=now,
        updated_at=now,
    )
    db.add(new_session)
    block.status = BlockStatus.ACTIVE.value
    block.updated_at = now
    db.commit()
    db.refresh(block)
    return block
