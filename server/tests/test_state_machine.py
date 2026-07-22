from datetime import datetime, timedelta, timezone

import pytest
from sqlalchemy import select

from app.models import PauseEvent, WorkSession
from app.state import effective_status, is_overdue
from app.state_machine import (
    ActiveSessionConflictError,
    InvalidTransitionError,
    cancel_block,
    complete_block,
    pause_block,
    reopen_block,
    reschedule_block,
    restart_block,
    resume_block,
    skip_block,
    start_block,
)
from app.status import BlockStatus

from .conftest import make_block


def test_start_from_planned_creates_session(db_session, user, now):
    block = make_block(db_session, user, now=now)
    updated = start_block(db_session, block, now)

    assert updated.status == BlockStatus.ACTIVE.value
    session = db_session.scalar(select(WorkSession).where(WorkSession.schedule_block_id == block.id))
    assert session is not None
    assert session.ended_at is None
    assert session.started_at == now


def test_start_is_idempotent(db_session, user, now):
    block = make_block(db_session, user, now=now)
    start_block(db_session, block, now)
    start_block(db_session, block, now + timedelta(seconds=1))  # duplicate call

    sessions = list(db_session.scalars(select(WorkSession).where(WorkSession.schedule_block_id == block.id)))
    assert len(sessions) == 1


def test_start_invalid_from_completed(db_session, user, now):
    block = make_block(db_session, user, status=BlockStatus.COMPLETED.value, now=now)
    with pytest.raises(InvalidTransitionError):
        start_block(db_session, block, now)


def test_start_another_block_auto_pauses_the_first(db_session, user, now):
    block_a = make_block(db_session, user, now=now)
    block_b = make_block(db_session, user, now=now)

    start_block(db_session, block_a, now)
    switch_at = now + timedelta(minutes=5)
    start_block(db_session, block_b, switch_at)

    db_session.refresh(block_a)
    db_session.refresh(block_b)
    assert block_a.status == BlockStatus.PAUSED.value
    assert block_b.status == BlockStatus.ACTIVE.value

    # Only one truly active session, but block_a's stays open (paused).
    session_a = db_session.scalar(select(WorkSession).where(WorkSession.schedule_block_id == block_a.id))
    session_b = db_session.scalar(select(WorkSession).where(WorkSession.schedule_block_id == block_b.id))
    assert session_a.status == "paused"
    assert session_a.ended_at is None
    assert session_b.status == "active"


def test_db_rejects_two_active_sessions_for_same_user(db_session, user, now):
    # The partial unique index is the last line of defense against a real
    # race (two starts landing in the same instant) that the auto-pause
    # pre-check in start_block() can't fully rule out.
    block_a = make_block(db_session, user, now=now)
    block_b = make_block(db_session, user, now=now)
    db_session.add(
        WorkSession(
            schedule_block_id=block_a.id, user_id=user.id, started_at=now,
            status="active", created_at=now, updated_at=now,
        )
    )
    db_session.commit()

    db_session.add(
        WorkSession(
            schedule_block_id=block_b.id, user_id=user.id, started_at=now,
            status="active", created_at=now, updated_at=now,
        )
    )
    with pytest.raises(Exception):
        db_session.commit()
    db_session.rollback()


def test_restart_resets_elapsed_and_discards_old_session(db_session, user, now):
    block = make_block(db_session, user, now=now)
    start_block(db_session, block, now)

    restart_at = now + timedelta(minutes=10)
    block = restart_block(db_session, block, restart_at)

    assert block.status == BlockStatus.ACTIVE.value
    sessions = list(
        db_session.scalars(
            select(WorkSession).where(WorkSession.schedule_block_id == block.id).order_by(WorkSession.started_at)
        )
    )
    assert len(sessions) == 2
    assert sessions[0].status == "restarted"
    assert sessions[0].ended_at == restart_at
    assert sessions[1].status == "active"
    assert sessions[1].started_at == restart_at
    assert sessions[1].ended_at is None


def test_restart_invalid_from_planned(db_session, user, now):
    block = make_block(db_session, user, now=now)
    with pytest.raises(InvalidTransitionError):
        restart_block(db_session, block, now)


def test_pause_resume_cycle(db_session, user, now):
    block = make_block(db_session, user, now=now)
    start_block(db_session, block, now)

    paused_at = now + timedelta(minutes=10)
    block = pause_block(db_session, block, paused_at)
    assert block.status == BlockStatus.PAUSED.value

    resumed_at = paused_at + timedelta(minutes=5)
    block = resume_block(db_session, block, resumed_at)
    assert block.status == BlockStatus.ACTIVE.value

    session = db_session.scalar(select(WorkSession).where(WorkSession.schedule_block_id == block.id))
    pause = db_session.scalar(select(PauseEvent).where(PauseEvent.work_session_id == session.id))
    assert pause.started_at == paused_at
    assert pause.ended_at == resumed_at


def test_pause_invalid_when_not_active(db_session, user, now):
    block = make_block(db_session, user, now=now)
    with pytest.raises(InvalidTransitionError):
        pause_block(db_session, block, now)


def test_complete_from_active_closes_session(db_session, user, now):
    block = make_block(db_session, user, now=now)
    start_block(db_session, block, now)

    completed_at = now + timedelta(hours=1)
    block = complete_block(db_session, block, completed_at)

    assert block.status == BlockStatus.COMPLETED.value
    assert block.completed_at == completed_at
    session = db_session.scalar(select(WorkSession).where(WorkSession.schedule_block_id == block.id))
    assert session.ended_at == completed_at


def test_complete_from_paused_closes_open_pause(db_session, user, now):
    block = make_block(db_session, user, now=now)
    start_block(db_session, block, now)
    pause_block(db_session, block, now + timedelta(minutes=10))

    completed_at = now + timedelta(minutes=30)
    block = complete_block(db_session, block, completed_at)

    session = db_session.scalar(select(WorkSession).where(WorkSession.schedule_block_id == block.id))
    pause = db_session.scalar(select(PauseEvent).where(PauseEvent.work_session_id == session.id))
    assert pause.ended_at == completed_at
    assert session.ended_at == completed_at


def test_skip_only_valid_from_planned(db_session, user, now):
    block = make_block(db_session, user, now=now)
    block = skip_block(db_session, block, now)
    assert block.status == BlockStatus.SKIPPED.value

    block2 = make_block(db_session, user, now=now)
    start_block(db_session, block2, now)
    with pytest.raises(InvalidTransitionError):
        skip_block(db_session, block2, now)


def test_cancel_from_active_closes_session(db_session, user, now):
    block = make_block(db_session, user, now=now)
    start_block(db_session, block, now)

    cancelled_at = now + timedelta(minutes=5)
    block = cancel_block(db_session, block, cancelled_at)

    assert block.status == BlockStatus.CANCELLED.value
    session = db_session.scalar(select(WorkSession).where(WorkSession.schedule_block_id == block.id))
    assert session.ended_at == cancelled_at


def test_cancel_invalid_from_terminal_state(db_session, user, now):
    block = make_block(db_session, user, status=BlockStatus.COMPLETED.value, now=now)
    with pytest.raises(InvalidTransitionError):
        cancel_block(db_session, block, now)


def test_reschedule_creates_linked_block(db_session, user, now):
    block = make_block(db_session, user, now=now)
    new_start = now + timedelta(days=1)
    new_end = new_start + timedelta(hours=1)

    new_block = reschedule_block(db_session, block, new_start, new_end, now)

    assert new_block.rescheduled_from_id == block.id
    assert new_block.status == BlockStatus.PLANNED.value
    db_session.refresh(block)
    assert block.status == BlockStatus.RESCHEDULED.value


def test_reopen_from_completed_clears_timestamp(db_session, user, now):
    block = make_block(db_session, user, now=now)
    start_block(db_session, block, now)
    completed_at = now + timedelta(hours=1)
    block = complete_block(db_session, block, completed_at)

    reopened_at = completed_at + timedelta(minutes=5)
    block = reopen_block(db_session, block, reopened_at)

    assert block.status == BlockStatus.PLANNED.value
    assert block.completed_at is None


def test_reopen_from_skipped_and_cancelled(db_session, user, now):
    skipped = make_block(db_session, user, now=now)
    skip_block(db_session, skipped, now)
    skipped = reopen_block(db_session, skipped, now + timedelta(minutes=1))
    assert skipped.status == BlockStatus.PLANNED.value
    assert skipped.skipped_at is None

    cancelled = make_block(db_session, user, now=now)
    start_block(db_session, cancelled, now)
    cancel_block(db_session, cancelled, now + timedelta(minutes=1))
    cancelled = reopen_block(db_session, cancelled, now + timedelta(minutes=2))
    assert cancelled.status == BlockStatus.PLANNED.value
    assert cancelled.cancelled_at is None


def test_reopen_invalid_from_active(db_session, user, now):
    block = make_block(db_session, user, now=now)
    start_block(db_session, block, now)
    with pytest.raises(InvalidTransitionError):
        reopen_block(db_session, block, now)


def test_reopen_is_idempotent_from_planned(db_session, user, now):
    block = make_block(db_session, user, now=now)
    reopened = reopen_block(db_session, block, now)
    assert reopened.status == BlockStatus.PLANNED.value


def test_effective_status_ready_is_not_persisted(db_session, user, now):
    block = make_block(db_session, user, planned_start=now, planned_end=now + timedelta(hours=1), now=now)
    later = now + timedelta(minutes=1)

    assert effective_status(block, later) == "ready"

    db_session.refresh(block)
    assert block.status == BlockStatus.PLANNED.value  # never mutated by a read


def test_overdue_detection(db_session, user, now):
    block = make_block(db_session, user, planned_start=now, planned_end=now + timedelta(hours=1), now=now)
    start_block(db_session, block, now)

    just_after_end = block.planned_end + timedelta(minutes=1)
    still_ok = effective_status(block, just_after_end)
    assert is_overdue(block, still_ok, just_after_end) is False

    well_after_end = block.planned_end + timedelta(minutes=20)
    overdue_status = effective_status(block, well_after_end)
    assert is_overdue(block, overdue_status, well_after_end) is True


def test_block_can_span_midnight(db_session, user, now):
    start = datetime(2026, 7, 22, 23, 50, tzinfo=timezone.utc)
    end = datetime(2026, 7, 23, 7, 0, tzinfo=timezone.utc)
    block = make_block(db_session, user, planned_start=start, planned_end=end, now=now)

    assert block.planned_end > block.planned_start
    assert block.planned_end.date() != block.planned_start.date()
    duration = block.planned_end - block.planned_start
    assert duration == timedelta(hours=7, minutes=10)
