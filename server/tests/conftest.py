from __future__ import annotations

import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.database import Base  # noqa: E402
from app.models import Project, ScheduleBlock, User  # noqa: E402
from app.status import BlockStatus  # noqa: E402


@pytest.fixture()
def db_session():
    engine = create_engine("sqlite:///:memory:", connect_args={"check_same_thread": False})
    Base.metadata.create_all(engine)
    session_local = sessionmaker(bind=engine, autoflush=False, autocommit=False)
    session = session_local()
    try:
        yield session
    finally:
        session.close()


@pytest.fixture()
def now():
    return datetime(2026, 7, 22, 10, 0, tzinfo=timezone.utc)


@pytest.fixture()
def user(db_session, now):
    u = User(email="test@example.com", created_at=now)
    db_session.add(u)
    db_session.commit()
    db_session.refresh(u)
    return u


@pytest.fixture()
def project(db_session, user):
    p = Project(user_id=user.id, name="Test project", priority=50, category="work")
    db_session.add(p)
    db_session.commit()
    db_session.refresh(p)
    return p


def make_block(
    db_session,
    user,
    project=None,
    status: str = BlockStatus.PLANNED.value,
    planned_start: datetime | None = None,
    planned_end: datetime | None = None,
    now: datetime | None = None,
) -> ScheduleBlock:
    now = now or datetime(2026, 7, 22, 10, 0, tzinfo=timezone.utc)
    planned_start = planned_start or now
    planned_end = planned_end or (planned_start + timedelta(hours=1))
    block = ScheduleBlock(
        user_id=user.id,
        project_id=project.id if project else None,
        block_type="work",
        title="Test block",
        planned_start=planned_start,
        planned_end=planned_end,
        status=status,
        created_at=now,
        updated_at=now,
    )
    db_session.add(block)
    db_session.commit()
    db_session.refresh(block)
    return block
