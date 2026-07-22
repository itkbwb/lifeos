from __future__ import annotations

from datetime import date, datetime, timedelta
from pathlib import Path
from typing import Generator, Optional

from fastapi import Depends, FastAPI, HTTPException
from sqlalchemy import select
from sqlalchemy.orm import Session, selectinload

from .database import SessionLocal
from .models import Project, ScheduleBlock, User, WorkSession
from .schemas import BlockCreate, BlockUpdate, RescheduleRequest
from .seed import PLAN_END_DATE, DEFAULT_USER_EMAIL, ensure_seed_data
from .serializers import (
    elapsed_seconds,
    find_open_session,
    paused_seconds,
    serialize_block,
    serialize_project_stat,
    serialize_work_session,
)
from .state_machine import (
    ActiveSessionConflictError,
    InvalidTransitionError,
    cancel_block,
    complete_block,
    pause_block,
    reschedule_block,
    restart_block,
    resume_block,
    skip_block,
    start_block,
)
from .tz import APP_TZ, now_utc

BASE_DIR = Path(__file__).resolve().parent
VERSION = (BASE_DIR.parent / "VERSION").read_text(encoding="utf-8").strip()

app = FastAPI(title="Life OS", version=VERSION)


def get_db() -> Generator[Session, None, None]:
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()


def get_current_user(db: Session = Depends(get_db)) -> User:
    user = db.scalar(select(User).where(User.email == DEFAULT_USER_EMAIL))
    if user is None:
        raise HTTPException(status_code=500, detail="Default user not seeded")
    return user


@app.on_event("startup")
def startup() -> None:
    ensure_seed_data()


def _get_block(db: Session, user: User, block_id: int) -> ScheduleBlock:
    block = db.scalar(
        select(ScheduleBlock)
        .options(selectinload(ScheduleBlock.project))
        .where(ScheduleBlock.id == block_id, ScheduleBlock.user_id == user.id)
    )
    if block is None:
        raise HTTPException(status_code=404, detail="Block not found")
    return block


def _day_bounds_utc(local_day: date) -> tuple[datetime, datetime]:
    """Aware UTC-equivalent bounds, suitable as ORM query bind params
    (UTCDateTime converts aware -> naive-UTC itself on bind)."""
    start_local = datetime.combine(local_day, datetime.min.time(), tzinfo=APP_TZ)
    end_local = start_local + timedelta(days=1)
    return start_local, end_local


def _get_day_blocks(db: Session, user: User, local_day: date) -> list[ScheduleBlock]:
    start_utc, end_utc = _day_bounds_utc(local_day)
    return list(
        db.scalars(
            select(ScheduleBlock)
            .options(selectinload(ScheduleBlock.project))
            .where(
                ScheduleBlock.user_id == user.id,
                ScheduleBlock.planned_start >= start_utc,
                ScheduleBlock.planned_start < end_utc,
            )
            .order_by(ScheduleBlock.planned_start)
        )
    )


def _actual_minutes(db: Session, block: ScheduleBlock, now: datetime) -> Optional[int]:
    session = db.scalar(
        select(WorkSession)
        .where(WorkSession.schedule_block_id == block.id)
        .order_by(WorkSession.started_at.desc())
    )
    if session is None:
        return None
    paused = paused_seconds(db, session, now)
    return elapsed_seconds(session, now, paused) // 60


def _planned_minutes(block: ScheduleBlock) -> int:
    return max(0, int((block.planned_end - block.planned_start).total_seconds() // 60))


@app.get("/api/now")
def get_now(
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    now_aware = now_utc()
    today_local = now_aware.astimezone(APP_TZ).date()
    blocks = _get_day_blocks(db, user, today_local)

    current_block = next(
        (
            b
            for b in blocks
            if b.planned_start <= now_aware < b.planned_end
            and b.status not in {"skipped", "cancelled", "rescheduled"}
        ),
        None,
    )
    next_block = next(
        (b for b in blocks if b.planned_start > now_aware and b.status == "planned"),
        None,
    )

    # Scoped to current_block specifically (not "any open session for the
    # user") - switching to a different task can leave this block paused
    # with its own open session, and that's what the UI needs to show.
    active_session = None
    if current_block is not None:
        active_session = db.scalar(
            select(WorkSession).where(
                WorkSession.schedule_block_id == current_block.id,
                WorkSession.ended_at.is_(None),
            )
        )

    minutes_late_starting = 0
    if current_block is not None and current_block.status == "planned" and active_session is None:
        late = (now_aware - current_block.planned_start).total_seconds() / 60
        minutes_late_starting = max(0, int(late))

    deviation_minutes = 0
    for b in blocks:
        if b.status == "completed":
            actual = _actual_minutes(db, b, now_aware)
            if actual is not None:
                deviation_minutes += actual - _planned_minutes(b)

    return {
        "date": today_local.isoformat(),
        "current_block": serialize_block(db, current_block, now_aware) if current_block else None,
        "active_work_session": (
            serialize_work_session(db, active_session, now_aware) if active_session else None
        ),
        "next_block": serialize_block(db, next_block, now_aware) if next_block else None,
        "day_deviation": {
            "minutes_late_starting": minutes_late_starting,
            "minutes_over_under_planned": deviation_minutes,
        },
    }


@app.get("/api/plan/day")
def get_plan_day(
    plan_date: Optional[date] = None,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    now = now_utc()
    target = plan_date or now.astimezone(APP_TZ).date()
    blocks = _get_day_blocks(db, user, target)
    return {
        "date": target.isoformat(),
        "plan_end_date": PLAN_END_DATE.isoformat(),
        "blocks": [serialize_block(db, b, now) for b in blocks],
    }


@app.get("/api/plan/week")
def get_plan_week(
    plan_date: Optional[date] = None,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    now = now_utc()
    target = plan_date or now.astimezone(APP_TZ).date()
    monday = target - timedelta(days=target.weekday())

    days = []
    for offset in range(7):
        day = monday + timedelta(days=offset)
        blocks = _get_day_blocks(db, user, day)
        total = sum(_planned_minutes(b) for b in blocks)
        productive = sum(
            _planned_minutes(b) for b in blocks if b.block_type in {"work", "health", "life"}
        )
        completed = 0
        for b in blocks:
            if b.status == "completed":
                actual = _actual_minutes(db, b, now)
                completed += actual if actual is not None else _planned_minutes(b)
        days.append(
            {
                "date": day.isoformat(),
                "weekday": day.strftime("%a"),
                "total_minutes": total,
                "productive_minutes": productive,
                "completed_minutes": completed,
                "block_count": len(blocks),
            }
        )
    return {"days": days}


@app.get("/api/projects")
def get_projects(
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    now = now_utc()
    target = now.astimezone(APP_TZ).date()
    monday = target - timedelta(days=target.weekday())
    sunday = monday + timedelta(days=6)
    start_utc, _ = _day_bounds_utc(monday)
    _, end_utc = _day_bounds_utc(sunday)

    projects = list(
        db.scalars(
            select(Project)
            .where(Project.user_id == user.id, Project.is_active.is_(True))
            .order_by(Project.priority.desc(), Project.name)
        )
    )
    result = []
    for project in projects:
        blocks = list(
            db.scalars(
                select(ScheduleBlock).where(
                    ScheduleBlock.project_id == project.id,
                    ScheduleBlock.planned_start >= start_utc,
                    ScheduleBlock.planned_start < end_utc,
                )
            )
        )
        result.append(serialize_project_stat(project, blocks))
    return result


@app.post("/api/blocks")
def create_block(
    payload: BlockCreate,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    if payload.planned_end <= payload.planned_start:
        raise HTTPException(status_code=400, detail="planned_end must be after planned_start")
    if payload.project_id is not None and db.get(Project, payload.project_id) is None:
        raise HTTPException(status_code=400, detail="Project not found")

    now = now_utc()
    block = ScheduleBlock(
        user_id=user.id,
        project_id=payload.project_id,
        block_type=payload.block_type,
        title=payload.title,
        description=payload.description,
        planned_start=payload.planned_start,
        planned_end=payload.planned_end,
        created_at=now,
        updated_at=now,
    )
    db.add(block)
    db.commit()
    db.refresh(block)
    return serialize_block(db, block, now)


@app.patch("/api/blocks/{block_id}")
def update_block(
    block_id: int,
    payload: BlockUpdate,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    block = _get_block(db, user, block_id)
    values = payload.model_dump(exclude_unset=True)

    future_start = values.get("planned_start", block.planned_start)
    future_end = values.get("planned_end", block.planned_end)
    if future_end <= future_start:
        raise HTTPException(status_code=400, detail="planned_end must be after planned_start")

    if "project_id" in values and values["project_id"] is not None:
        if db.get(Project, values["project_id"]) is None:
            raise HTTPException(status_code=400, detail="Project not found")

    for key, value in values.items():
        setattr(block, key, value)
    block.updated_at = now_utc()
    db.commit()
    db.refresh(block)
    return serialize_block(db, block, now_utc())


@app.delete("/api/blocks/{block_id}")
def delete_block(
    block_id: int,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    block = _get_block(db, user, block_id)
    db.delete(block)
    db.commit()
    return {"ok": True}


def _run_transition(fn, db: Session, block: ScheduleBlock, now: datetime):
    try:
        return fn(db, block, now)
    except InvalidTransitionError as exc:
        raise HTTPException(status_code=409, detail=exc.message) from exc
    except ActiveSessionConflictError as exc:
        raise HTTPException(
            status_code=409,
            detail={"error": "active_session_conflict", "active_work_session_id": exc.active_work_session_id},
        ) from exc


@app.post("/api/blocks/{block_id}/start")
def action_start(block_id: int, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    block = _get_block(db, user, block_id)
    now = now_utc()
    block = _run_transition(start_block, db, block, now)
    return serialize_block(db, block, now)


@app.post("/api/blocks/{block_id}/pause")
def action_pause(block_id: int, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    block = _get_block(db, user, block_id)
    now = now_utc()
    block = _run_transition(pause_block, db, block, now)
    return serialize_block(db, block, now)


@app.post("/api/blocks/{block_id}/resume")
def action_resume(block_id: int, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    block = _get_block(db, user, block_id)
    now = now_utc()
    block = _run_transition(resume_block, db, block, now)
    return serialize_block(db, block, now)


@app.post("/api/blocks/{block_id}/complete")
def action_complete(block_id: int, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    block = _get_block(db, user, block_id)
    now = now_utc()
    block = _run_transition(complete_block, db, block, now)
    return serialize_block(db, block, now)


@app.post("/api/blocks/{block_id}/restart")
def action_restart(block_id: int, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    block = _get_block(db, user, block_id)
    now = now_utc()
    block = _run_transition(restart_block, db, block, now)
    return serialize_block(db, block, now)


@app.post("/api/blocks/{block_id}/skip")
def action_skip(block_id: int, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    block = _get_block(db, user, block_id)
    now = now_utc()
    block = _run_transition(skip_block, db, block, now)
    return serialize_block(db, block, now)


@app.post("/api/blocks/{block_id}/cancel")
def action_cancel(block_id: int, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    block = _get_block(db, user, block_id)
    now = now_utc()
    block = _run_transition(cancel_block, db, block, now)
    return serialize_block(db, block, now)


@app.post("/api/blocks/{block_id}/reschedule")
def action_reschedule(
    block_id: int,
    payload: RescheduleRequest,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    block = _get_block(db, user, block_id)
    now = now_utc()
    try:
        new_block = reschedule_block(db, block, payload.planned_start, payload.planned_end, now)
    except InvalidTransitionError as exc:
        raise HTTPException(status_code=409, detail=exc.message) from exc
    return serialize_block(db, new_block, now)


@app.get("/health")
def health():
    return {
        "status": "ok",
        "version": VERSION,
        "time": now_utc().isoformat(),
    }
