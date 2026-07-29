from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

from fastapi import Depends, FastAPI, HTTPException, Query, Response
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app import models, schemas
from app.database import Base, engine, get_db

BASE_DIR = Path(__file__).resolve().parent
VERSION = (BASE_DIR.parent / "VERSION").read_text(encoding="utf-8").strip()

app = FastAPI(title="Life OS", version=VERSION)

Base.metadata.create_all(bind=engine)


@app.get("/health")
def health():
    return {
        "status": "ok",
        "version": VERSION,
        "time": datetime.now(timezone.utc).isoformat(),
    }


@app.get("/api/projects", response_model=list[schemas.ProjectOut])
def list_projects(db: Session = Depends(get_db)):
    return db.query(models.Project).order_by(models.Project.created_at.asc()).all()


@app.post("/api/projects", response_model=schemas.ProjectOut, status_code=201)
def create_project(payload: schemas.ProjectCreate, db: Session = Depends(get_db)):
    project = models.Project(name=payload.name, color=payload.color)
    db.add(project)
    db.commit()
    db.refresh(project)
    return project


@app.patch("/api/projects/{project_id}", response_model=schemas.ProjectOut)
def update_project(project_id: int, payload: schemas.ProjectUpdate, db: Session = Depends(get_db)):
    project = db.get(models.Project, project_id)
    if project is None:
        raise HTTPException(status_code=404, detail="Project not found")
    if payload.name is not None:
        project.name = payload.name
    if payload.color is not None:
        project.color = payload.color
    db.commit()
    db.refresh(project)
    return project


@app.delete("/api/projects/{project_id}", status_code=204)
def delete_project(project_id: int, db: Session = Depends(get_db)):
    project = db.get(models.Project, project_id)
    if project is None:
        raise HTTPException(status_code=404, detail="Project not found")
    db.delete(project)
    try:
        db.commit()
    except IntegrityError:
        db.rollback()
        raise HTTPException(status_code=409, detail="project has events; cannot delete")


def _live_end_after(db: Session, project_id: int, after: datetime) -> Optional[models.Event]:
    return (
        db.query(models.Event)
        .filter(
            models.Event.project_id == project_id,
            models.Event.type == "end",
            models.Event.superseded_by_id.is_(None),
            models.Event.occurred_at >= after,
        )
        .order_by(models.Event.occurred_at.asc())
        .first()
    )


def get_active_start(db: Session, exclude_event_id: Optional[int] = None) -> Optional[models.Event]:
    """The currently open START event (system-wide), or None if no project is active.

    Determined by replaying every start/end in true chronological order (not by
    checking "does *any* later end exist" per start) - otherwise a backdated or
    corrected event inserted out of insertion-order could be wrongly matched
    against an end that actually belongs to a different, already-closed session.
    """
    query = db.query(models.Event).filter(
        models.Event.type.in_(["start", "end"]), models.Event.superseded_by_id.is_(None)
    )
    if exclude_event_id is not None:
        query = query.filter(models.Event.id != exclude_event_id)
    events = query.order_by(models.Event.occurred_at.asc()).all()

    open_starts: dict[int, models.Event] = {}
    for event in events:
        if event.type == "start":
            open_starts[event.project_id] = event
        else:
            open_starts.pop(event.project_id, None)

    if not open_starts:
        return None
    # Only one project should ever be open at once; if more somehow are, the
    # most recently started one wins.
    return max(open_starts.values(), key=lambda e: e.occurred_at)


@app.post("/api/events", response_model=schemas.EventOut, status_code=201)
def create_event(payload: schemas.EventCreate, db: Session = Depends(get_db)):
    project = db.get(models.Project, payload.project_id)
    if project is None:
        raise HTTPException(status_code=404, detail="Project not found")

    if payload.type == "start":
        active = get_active_start(db)
        if active is not None:
            raise HTTPException(
                status_code=409,
                detail={
                    "message": "another project is already active",
                    "active_project_id": active.project_id,
                    "active_event_id": active.id,
                    "started_at": active.occurred_at.isoformat(),
                },
            )
    elif payload.type == "end":
        active = get_active_start(db)
        if active is None or active.project_id != payload.project_id:
            raise HTTPException(status_code=409, detail="no active session for this project")

    event = models.Event(
        project_id=payload.project_id,
        type=payload.type,
        occurred_at=payload.occurred_at or datetime.now(timezone.utc),
        label=payload.label,
    )
    db.add(event)
    db.commit()
    db.refresh(event)
    return event


@app.get("/api/events", response_model=list[schemas.EventOut])
def list_events(
    project_id: Optional[int] = None,
    from_: Optional[datetime] = Query(None, alias="from"),
    to: Optional[datetime] = None,
    db: Session = Depends(get_db),
):
    query = db.query(models.Event).filter(models.Event.superseded_by_id.is_(None))
    if project_id is not None:
        query = query.filter(models.Event.project_id == project_id)

    events = []
    if from_ is not None or to is not None:
        windowed = query
        if from_ is not None:
            windowed = windowed.filter(models.Event.occurred_at >= from_)
        if to is not None:
            windowed = windowed.filter(models.Event.occurred_at < to)
        events = windowed.order_by(models.Event.occurred_at.asc()).all()

        if from_ is not None:
            # A session that started before `from` but is still open (or ends
            # at/after `from`) needs its START included too, so the client can
            # pair it with whatever END/still-open state falls in this window.
            dangling_query = query.filter(
                models.Event.type == "start", models.Event.occurred_at < from_
            )
            dangling_starts = dangling_query.order_by(models.Event.occurred_at.desc()).all()
            seen_ids = {e.id for e in events}
            for start in dangling_starts:
                end = _live_end_after(db, start.project_id, start.occurred_at)
                if (end is None or end.occurred_at >= from_) and start.id not in seen_ids:
                    events.append(start)
                    seen_ids.add(start.id)
        events.sort(key=lambda e: e.occurred_at)
    else:
        events = query.order_by(models.Event.occurred_at.asc()).all()

    return events


@app.get("/api/events/active")
def get_active_project_endpoint(db: Session = Depends(get_db)):
    active = get_active_start(db)
    if active is None:
        return Response(status_code=204)
    return schemas.ActiveProjectOut(
        project_id=active.project_id, event_id=active.id, started_at=active.occurred_at
    )


@app.post("/api/events/{event_id}/correct", response_model=schemas.EventOut)
def correct_event(event_id: int, payload: schemas.EventCorrect, db: Session = Depends(get_db)):
    original = db.get(models.Event, event_id)
    if original is None:
        raise HTTPException(status_code=404, detail="Event not found")
    if original.superseded_by_id is not None:
        raise HTTPException(status_code=409, detail="event was already corrected")

    new_project_id = payload.project_id if payload.project_id is not None else original.project_id
    new_type = payload.type if payload.type is not None else original.type
    new_occurred_at = payload.occurred_at if payload.occurred_at is not None else original.occurred_at
    new_label = payload.label if payload.label is not None else original.label

    if db.get(models.Project, new_project_id) is None:
        raise HTTPException(status_code=404, detail="Project not found")

    if new_type == "start":
        active = get_active_start(db, exclude_event_id=original.id)
        if active is not None:
            raise HTTPException(
                status_code=409,
                detail={
                    "message": "another project is already active",
                    "active_project_id": active.project_id,
                    "active_event_id": active.id,
                    "started_at": active.occurred_at.isoformat(),
                },
            )

    corrected = models.Event(
        project_id=new_project_id,
        type=new_type,
        occurred_at=new_occurred_at,
        label=new_label,
        corrects_id=original.id,
    )
    db.add(corrected)
    db.flush()

    original.superseded_by_id = corrected.id
    original.corrected_at = datetime.now(timezone.utc)

    db.commit()
    db.refresh(corrected)
    return corrected
