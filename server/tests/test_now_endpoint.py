from datetime import timedelta

from app.models import PauseEvent, WorkSession
from app.serializers import elapsed_seconds, paused_seconds

from .conftest import make_block


def test_elapsed_excludes_paused_time(db_session, user, now):
    block = make_block(db_session, user, now=now)

    session = WorkSession(
        schedule_block_id=block.id,
        user_id=user.id,
        started_at=now,
        status="paused",
        created_at=now,
        updated_at=now,
    )
    db_session.add(session)
    db_session.commit()
    db_session.refresh(session)

    # Paused for 5 minutes, then resumed.
    pause_start = now + timedelta(minutes=2)
    pause_end = pause_start + timedelta(minutes=5)
    db_session.add(
        PauseEvent(
            work_session_id=session.id,
            started_at=pause_start,
            ended_at=pause_end,
            created_at=pause_start,
        )
    )
    db_session.commit()

    check_at = now + timedelta(minutes=20)
    paused = paused_seconds(db_session, session, check_at)
    elapsed = elapsed_seconds(session, check_at, paused)

    assert paused == 5 * 60
    assert elapsed == 15 * 60  # 20 min wall clock minus 5 min paused


def test_elapsed_counts_still_open_pause_up_to_now(db_session, user, now):
    block = make_block(db_session, user, now=now)
    session = WorkSession(
        schedule_block_id=block.id,
        user_id=user.id,
        started_at=now,
        status="paused",
        created_at=now,
        updated_at=now,
    )
    db_session.add(session)
    db_session.commit()
    db_session.refresh(session)

    pause_start = now + timedelta(minutes=2)
    db_session.add(
        PauseEvent(work_session_id=session.id, started_at=pause_start, created_at=pause_start)
    )
    db_session.commit()

    check_at = now + timedelta(minutes=10)
    paused = paused_seconds(db_session, session, check_at)
    elapsed = elapsed_seconds(session, check_at, paused)

    # Still paused: 8 of the 10 minutes are paused time (started pausing at +2min)
    assert paused == 8 * 60
    assert elapsed == 2 * 60
