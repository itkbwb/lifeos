from __future__ import annotations

from datetime import date, datetime, timedelta
from pathlib import Path
from typing import Generator

from fastapi import Depends, FastAPI, Form, HTTPException, Request
from fastapi.responses import HTMLResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from sqlalchemy import func, select
from sqlalchemy.orm import Session, selectinload

from .database import Base, SessionLocal, engine
from .models import CompletionLog, Project, RoutineItem, ScheduleBlock
from .schemas import BlockCreate, BlockUpdate
from .seed import PLAN_END_DATE, ensure_seed_data

BASE_DIR = Path(__file__).resolve().parent
VERSION = (BASE_DIR.parent / "VERSION").read_text(encoding="utf-8").strip()

app = FastAPI(title="Life OS", version=VERSION)
app.mount("/static", StaticFiles(directory=BASE_DIR / "static"), name="static")
templates = Jinja2Templates(directory=BASE_DIR / "templates")


def get_db() -> Generator[Session, None, None]:
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()


@app.on_event("startup")
def startup() -> None:
    Base.metadata.create_all(bind=engine)
    ensure_seed_data()


def _duration_minutes(block: ScheduleBlock) -> int:
    start = block.start_time.hour * 60 + block.start_time.minute
    end = block.end_time.hour * 60 + block.end_time.minute
    return max(0, end - start)


def serialize_block(block: ScheduleBlock) -> dict:
    return {
        "id": block.id,
        "block_date": block.block_date.isoformat(),
        "start_time": block.start_time.strftime("%H:%M"),
        "end_time": block.end_time.strftime("%H:%M"),
        "duration_minutes": _duration_minutes(block),
        "title": block.title,
        "notes": block.notes,
        "block_type": block.block_type,
        "status": block.status,
        "project_id": block.project_id,
        "project_name": block.project.name if block.project else None,
    }


def get_day_blocks(db: Session, target: date) -> list[ScheduleBlock]:
    return list(
        db.scalars(
            select(ScheduleBlock)
            .options(selectinload(ScheduleBlock.project))
            .where(ScheduleBlock.block_date == target)
            .order_by(ScheduleBlock.start_time)
        )
    )


def get_week_payload(db: Session, target: date) -> list[dict]:
    monday = target - timedelta(days=target.weekday())
    payload: list[dict] = []

    for offset in range(7):
        day = monday + timedelta(days=offset)
        blocks = get_day_blocks(db, day)
        total = sum(_duration_minutes(b) for b in blocks)
        productive = sum(
            _duration_minutes(b)
            for b in blocks
            if b.block_type in {"work", "health", "life"}
        )
        completed = sum(
            _duration_minutes(b)
            for b in blocks
            if b.status == "completed"
        )
        payload.append(
            {
                "date": day.isoformat(),
                "weekday": day.strftime("%a"),
                "total_minutes": total,
                "productive_minutes": productive,
                "completed_minutes": completed,
                "block_count": len(blocks),
            }
        )
    return payload


def get_project_payload(db: Session, target: date) -> list[dict]:
    monday = target - timedelta(days=target.weekday())
    sunday = monday + timedelta(days=6)

    projects = list(
        db.scalars(
            select(Project)
            .where(Project.is_active.is_(True))
            .order_by(Project.priority.desc(), Project.name)
        )
    )

    result: list[dict] = []
    for project in projects:
        blocks = list(
            db.scalars(
                select(ScheduleBlock)
                .where(
                    ScheduleBlock.project_id == project.id,
                    ScheduleBlock.block_date >= monday,
                    ScheduleBlock.block_date <= sunday,
                )
            )
        )
        scheduled = sum(_duration_minutes(b) for b in blocks)
        completed = sum(
            _duration_minutes(b)
            for b in blocks
            if b.status == "completed"
        )
        result.append(
            {
                "id": project.id,
                "name": project.name,
                "priority": project.priority,
                "category": project.category,
                "scheduled_minutes": scheduled,
                "completed_minutes": completed,
                "block_count": len(blocks),
            }
        )
    return result


@app.get("/", response_class=HTMLResponse)
def index(request: Request, db: Session = Depends(get_db)):
    today = date.today()
    blocks = get_day_blocks(db, today)
    projects = list(
        db.scalars(
            select(Project)
            .where(Project.is_active.is_(True))
            .order_by(Project.priority.desc())
        )
    )

    return templates.TemplateResponse(
        request=request,
        name="index.html",
        context={
            "version": VERSION,
            "today": today.isoformat(),
            "plan_end_date": PLAN_END_DATE.isoformat(),
            "blocks": [serialize_block(b) for b in blocks],
            "week": get_week_payload(db, today),
            "project_stats": get_project_payload(db, today),
            "projects": [
                {
                    "id": p.id,
                    "name": p.name,
                    "priority": p.priority,
                    "category": p.category,
                }
                for p in projects
            ],
        },
    )


@app.get("/api/dashboard")
def dashboard(
    block_date: date | None = None,
    db: Session = Depends(get_db),
):
    target = block_date or date.today()
    return {
        "date": target.isoformat(),
        "plan_end_date": PLAN_END_DATE.isoformat(),
        "blocks": [serialize_block(b) for b in get_day_blocks(db, target)],
        "week": get_week_payload(db, target),
        "projects": get_project_payload(db, target),
    }


@app.get("/api/blocks")
def get_blocks(
    block_date: date | None = None,
    db: Session = Depends(get_db),
):
    target = block_date or date.today()
    return [serialize_block(b) for b in get_day_blocks(db, target)]


@app.post("/api/blocks")
def create_block(payload: BlockCreate, db: Session = Depends(get_db)):
    if payload.end_time <= payload.start_time:
        raise HTTPException(status_code=400, detail="End time must be after start time")

    if payload.project_id is not None and db.get(Project, payload.project_id) is None:
        raise HTTPException(status_code=400, detail="Project not found")

    block = ScheduleBlock(**payload.model_dump())
    db.add(block)
    db.commit()
    db.refresh(block)

    block = db.scalar(
        select(ScheduleBlock)
        .options(selectinload(ScheduleBlock.project))
        .where(ScheduleBlock.id == block.id)
    )
    return serialize_block(block)


@app.patch("/api/blocks/{block_id}")
def update_block(
    block_id: int,
    payload: BlockUpdate,
    db: Session = Depends(get_db),
):
    block = db.get(ScheduleBlock, block_id)
    if block is None:
        raise HTTPException(status_code=404, detail="Block not found")

    values = payload.model_dump(exclude_unset=True)
    future_start = values.get("start_time", block.start_time)
    future_end = values.get("end_time", block.end_time)

    if future_end <= future_start:
        raise HTTPException(status_code=400, detail="End time must be after start time")

    if "project_id" in values and values["project_id"] is not None:
        if db.get(Project, values["project_id"]) is None:
            raise HTTPException(status_code=400, detail="Project not found")

    for key, value in values.items():
        setattr(block, key, value)

    db.commit()

    block = db.scalar(
        select(ScheduleBlock)
        .options(selectinload(ScheduleBlock.project))
        .where(ScheduleBlock.id == block.id)
    )
    return serialize_block(block)


@app.post("/api/blocks/{block_id}/action")
def block_action(
    block_id: int,
    action: str = Form(...),
    db: Session = Depends(get_db),
):
    block = db.get(ScheduleBlock, block_id)
    if block is None:
        raise HTTPException(status_code=404, detail="Block not found")

    if action not in {"completed", "skipped", "planned"}:
        raise HTTPException(status_code=400, detail="Invalid action")

    block.status = action
    db.add(CompletionLog(block_id=block.id, action=action))
    db.commit()
    return {"ok": True, "status": action}


@app.delete("/api/blocks/{block_id}")
def delete_block(block_id: int, db: Session = Depends(get_db)):
    block = db.get(ScheduleBlock, block_id)
    if block is None:
        raise HTTPException(status_code=404, detail="Block not found")

    db.delete(block)
    db.commit()
    return {"ok": True}


@app.get("/api/projects")
def projects(db: Session = Depends(get_db)):
    return get_project_payload(db, date.today())


@app.get("/api/routines")
def routines(db: Session = Depends(get_db)):
    items = list(
        db.scalars(
            select(RoutineItem)
            .where(RoutineItem.is_active.is_(True))
            .order_by(RoutineItem.routine_name, RoutineItem.position)
        )
    )
    return [
        {
            "id": item.id,
            "routine_name": item.routine_name,
            "title": item.title,
            "position": item.position,
        }
        for item in items
    ]


@app.get("/health")
def health():
    return {
        "status": "ok",
        "version": VERSION,
        "time": datetime.now().isoformat(),
    }


@app.get("/manifest.json", include_in_schema=False)
def manifest():
    return JSONResponse(
        {
            "name": "Life OS",
            "short_name": "Life OS",
            "description": "Personal mission control",
            "start_url": "/",
            "display": "standalone",
            "orientation": "portrait",
            "background_color": "#09080d",
            "theme_color": "#09080d",
            "icons": [
                {
                    "src": "/static/icon.svg",
                    "sizes": "any",
                    "type": "image/svg+xml",
                    "purpose": "any maskable",
                }
            ],
        }
    )
