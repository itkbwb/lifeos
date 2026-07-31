from __future__ import annotations

import csv
import io
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Optional

from fastapi import Depends, FastAPI, HTTPException, Query, Response
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app import models, schemas
from app import scheduler as notification_scheduler
from app.database import Base, engine, ensure_schema_migrations, get_db

BASE_DIR = Path(__file__).resolve().parent
VERSION = (BASE_DIR.parent / "VERSION").read_text(encoding="utf-8").strip()

app = FastAPI(title="Life OS", version=VERSION)

Base.metadata.create_all(bind=engine)
ensure_schema_migrations()
notification_scheduler.start()


@app.get("/health")
def health():
    return {
        "status": "ok",
        "version": VERSION,
        "time": datetime.now(timezone.utc).isoformat(),
    }


@app.get("/repos/{owner}/{repo}/releases/latest")
def fake_github_latest_release(owner: str, repo: str):
    """Stub for GitHub's release API, hit only by debug builds (see UpdateChecker /
    UPDATE_CHECK_BASE_URL in build.gradle.kts) - always reports "no update" so the
    emulator never gets hijacked by the real update-available dialog mid-test.
    """
    return {"tag_name": "v0.0.0", "assets": []}


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
    if payload.archived is not None:
        project.archived = payload.archived
    db.commit()
    db.refresh(project)
    return project


@app.delete("/api/projects/{project_id}", status_code=204)
def delete_project(project_id: int, force: bool = False, db: Session = Depends(get_db)):
    project = db.get(models.Project, project_id)
    if project is None:
        raise HTTPException(status_code=404, detail="Project not found")

    if force:
        # Clear the project's own Events/PlanEntries first (PlanEntry
        # deletion cascades to its PlanChanges) so the RESTRICT FK no
        # longer blocks deleting the project itself.
        db.query(models.Event).filter(models.Event.project_id == project_id).delete(
            synchronize_session=False
        )
        db.query(models.PlanEntry).filter(models.PlanEntry.project_id == project_id).delete(
            synchronize_session=False
        )
        db.delete(project)
        db.commit()
        return

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


@app.delete("/api/events/{event_id}", status_code=204)
def delete_event(event_id: int, db: Session = Depends(get_db)):
    event = db.get(models.Event, event_id)
    if event is None:
        raise HTTPException(status_code=404, detail="Event not found")
    db.delete(event)
    db.commit()


@app.post("/api/plan/entries", response_model=schemas.PlanEntryOut, status_code=201)
def create_plan_entry(payload: schemas.PlanEntryCreate, db: Session = Depends(get_db)):
    project = db.get(models.Project, payload.project_id)
    if project is None:
        raise HTTPException(status_code=404, detail="Project not found")

    entry = models.PlanEntry(
        project_id=payload.project_id,
        start_time=payload.start_time,
        end_time=payload.end_time,
        name=payload.name,
    )
    db.add(entry)
    db.commit()
    db.refresh(entry)
    return entry


@app.get("/api/plan/entries", response_model=list[schemas.PlanEntryOut])
def list_plan_entries(
    project_id: Optional[int] = None,
    from_: Optional[datetime] = Query(None, alias="from"),
    to: Optional[datetime] = None,
    db: Session = Depends(get_db),
):
    query = db.query(models.PlanEntry)
    if project_id is not None:
        query = query.filter(models.PlanEntry.project_id == project_id)
    if from_ is not None:
        query = query.filter(models.PlanEntry.end_time >= from_)
    if to is not None:
        query = query.filter(models.PlanEntry.start_time < to)
    return query.order_by(models.PlanEntry.start_time.asc()).all()


@app.patch("/api/plan/entries/{entry_id}", response_model=schemas.PlanEntryOut)
def update_plan_entry(
    entry_id: int, payload: schemas.PlanEntryUpdate, db: Session = Depends(get_db)
):
    """Direct mutation of a Static Plan entry (chapter 5.7) - a data-entry
    correction, distinct from a PlanChange (which records a real-world
    reschedule and only ever touches the Dynamic Plan)."""
    entry = db.get(models.PlanEntry, entry_id)
    if entry is None:
        raise HTTPException(status_code=404, detail="Plan entry not found")

    new_project_id = payload.project_id if payload.project_id is not None else entry.project_id
    new_start_time = payload.start_time if payload.start_time is not None else entry.start_time
    new_end_time = payload.end_time if payload.end_time is not None else entry.end_time
    new_name = payload.name if "name" in payload.model_fields_set else entry.name

    if new_end_time <= new_start_time:
        raise HTTPException(status_code=422, detail="end_time must be after start_time")
    if db.get(models.Project, new_project_id) is None:
        raise HTTPException(status_code=404, detail="Project not found")

    entry.project_id = new_project_id
    entry.start_time = new_start_time
    entry.end_time = new_end_time
    entry.name = new_name
    db.commit()
    db.refresh(entry)
    return entry


@app.delete("/api/plan/entries/{entry_id}", status_code=204)
def delete_plan_entry(entry_id: int, db: Session = Depends(get_db)):
    entry = db.get(models.PlanEntry, entry_id)
    if entry is None:
        raise HTTPException(status_code=404, detail="Plan entry not found")
    db.delete(entry)
    db.commit()


@app.post(
    "/api/plan/entries/{entry_id}/changes",
    response_model=schemas.PlanChangeOut,
    status_code=201,
)
def create_plan_change(
    entry_id: int, payload: schemas.PlanChangeCreate, db: Session = Depends(get_db)
):
    entry = db.get(models.PlanEntry, entry_id)
    if entry is None:
        raise HTTPException(status_code=404, detail="Plan entry not found")

    change = models.PlanChange(
        plan_entry_id=entry_id,
        change_type=payload.change_type,
        new_start_time=payload.new_start_time,
        new_end_time=payload.new_end_time,
    )
    db.add(change)
    db.commit()
    db.refresh(change)
    return change


@app.delete("/api/plan/changes/{change_id}", status_code=204)
def delete_plan_change(change_id: int, db: Session = Depends(get_db)):
    """Undo a PlanChange (chapter 5.10) - removing it restores the Dynamic Plan
    to whatever the next-latest change (or the Static entry itself) says."""
    change = db.get(models.PlanChange, change_id)
    if change is None:
        raise HTTPException(status_code=404, detail="Plan change not found")
    db.delete(change)
    db.commit()


def _latest_changes_by_entry(db: Session, entry_ids: list[int]) -> dict[int, models.PlanChange]:
    if not entry_ids:
        return {}
    changes = (
        db.query(models.PlanChange)
        .filter(models.PlanChange.plan_entry_id.in_(entry_ids))
        .order_by(models.PlanChange.created_at.asc())
        .all()
    )
    latest: dict[int, models.PlanChange] = {}
    for change in changes:
        latest[change.plan_entry_id] = change
    return latest


@app.get("/api/plan/dynamic", response_model=list[schemas.DynamicPlanEntryOut])
def get_dynamic_plan(
    project_id: Optional[int] = None,
    from_: Optional[datetime] = Query(None, alias="from"),
    to: Optional[datetime] = None,
    db: Session = Depends(get_db),
):
    query = db.query(models.PlanEntry)
    if project_id is not None:
        query = query.filter(models.PlanEntry.project_id == project_id)
    entries = query.all()

    latest_changes = _latest_changes_by_entry(db, [e.id for e in entries])

    result = []
    for entry in entries:
        change = latest_changes.get(entry.id)
        if change is not None and change.change_type == "cancel":
            continue
        start_time = entry.start_time
        end_time = entry.end_time
        if change is not None and change.change_type == "move":
            start_time = change.new_start_time
            end_time = change.new_end_time

        if from_ is not None and end_time < from_:
            continue
        if to is not None and start_time >= to:
            continue

        result.append(
            schemas.DynamicPlanEntryOut(
                id=entry.id,
                project_id=entry.project_id,
                start_time=start_time,
                end_time=end_time,
                name=entry.name,
            )
        )

    result.sort(key=lambda e: e.start_time)
    return result


_IMPORT_COLORS = sorted(schemas.PROJECT_COLORS)


@app.post("/api/import/csv", response_model=schemas.ImportResult)
def import_csv(payload: schemas.ImportRequest, db: Session = Depends(get_db)):
    """Import creates only Static records (chapter 5.11); missing projects are
    created automatically, and no source information is stored. Columns:
    project,date,start,end,title (title optional). date=YYYY-MM-DD,
    start/end=HH:MM, interpreted at tz_offset_minutes."""
    tz = timezone(timedelta(minutes=payload.tz_offset_minutes))

    projects_by_name: dict[str, models.Project] = {
        p.name: p for p in db.query(models.Project).all()
    }
    projects_created: list[str] = []
    errors: list[schemas.ImportRowError] = []
    created = 0

    reader = csv.DictReader(io.StringIO(payload.csv))
    if reader.fieldnames is None:
        raise HTTPException(status_code=422, detail="CSV has no header row")
    missing = {"project", "date", "start", "end"} - {f.strip() for f in reader.fieldnames}
    if missing:
        raise HTTPException(status_code=422, detail=f"CSV missing columns: {sorted(missing)}")

    for row_num, row in enumerate(reader, start=2):
        try:
            project_name = (row.get("project") or "").strip()
            if not project_name:
                raise ValueError("project must not be empty")
            date_str = (row.get("date") or "").strip()
            start_str = (row.get("start") or "").strip()
            end_str = (row.get("end") or "").strip()
            title = (row.get("title") or "").strip() or None

            date = datetime.strptime(date_str, "%Y-%m-%d").date()
            start_time_local = datetime.strptime(start_str, "%H:%M").time()
            end_time_local = datetime.strptime(end_str, "%H:%M").time()
            start_dt = datetime.combine(date, start_time_local, tzinfo=tz)
            end_dt = datetime.combine(date, end_time_local, tzinfo=tz)
            if end_dt <= start_dt:
                raise ValueError("end must be after start")

            project = projects_by_name.get(project_name)
            if project is None:
                color = _IMPORT_COLORS[len(projects_by_name) % len(_IMPORT_COLORS)]
                project = models.Project(name=project_name, color=color)
                db.add(project)
                db.flush()
                projects_by_name[project_name] = project
                projects_created.append(project_name)

            db.add(
                models.PlanEntry(
                    project_id=project.id,
                    start_time=start_dt,
                    end_time=end_dt,
                    name=title,
                )
            )
            created += 1
        except (ValueError, KeyError) as exc:
            errors.append(schemas.ImportRowError(row=row_num, message=str(exc)))

    db.commit()
    return schemas.ImportResult(created=created, projects_created=projects_created, errors=errors)


@app.post("/api/admin/clear", response_model=schemas.ClearResult)
def clear_data(payload: schemas.ClearRequest, db: Session = Depends(get_db)):
    scope = payload.scope
    deleted_events = 0
    deleted_plan_entries = 0
    deleted_plan_changes = 0
    deleted_projects = 0

    if scope in ("timeline", "all", "projects"):
        deleted_events += (
            db.query(models.Event)
            .filter(models.Event.type.in_(["start", "end"]))
            .delete(synchronize_session=False)
        )
    if scope in ("instant", "all", "projects"):
        deleted_events += (
            db.query(models.Event)
            .filter(models.Event.type == "instant")
            .delete(synchronize_session=False)
        )
    if scope == "dynamic":
        deleted_plan_changes += db.query(models.PlanChange).delete(synchronize_session=False)
    if scope in ("static", "static_and_dynamic", "all", "projects"):
        # PlanEntry deletion cascades to its PlanChanges at the DB level (see
        # database.py's foreign_keys=ON pragma) - count them first to report.
        deleted_plan_changes += db.query(models.PlanChange).count()
        deleted_plan_entries += db.query(models.PlanEntry).delete(synchronize_session=False)
    if scope == "projects":
        # Events/PlanEntries above are already cleared this same transaction,
        # so the RESTRICT FK from either table no longer blocks this delete.
        deleted_projects += db.query(models.Project).delete(synchronize_session=False)

    db.commit()
    return schemas.ClearResult(
        deleted_events=deleted_events,
        deleted_plan_entries=deleted_plan_entries,
        deleted_plan_changes=deleted_plan_changes,
        deleted_projects=deleted_projects,
    )


@app.post("/api/notifications/register", status_code=204)
def register_device_token(payload: schemas.DeviceTokenRegister, db: Session = Depends(get_db)):
    """Registers an FCM token so the server-side scheduler (app/scheduler.py)
    can push start/stop suggestions to it. Idempotent - re-registering the
    same token (e.g. on every app launch) is a no-op."""
    existing = db.query(models.DeviceToken).filter(models.DeviceToken.token == payload.token).first()
    if existing is None:
        db.add(models.DeviceToken(token=payload.token))
        db.commit()


@app.post("/api/notifications/unregister", status_code=204)
def unregister_device_token(payload: schemas.DeviceTokenRegister, db: Session = Depends(get_db)):
    """Un-registers a token (e.g. the user turned the Settings toggle off) -
    idempotent, no error if the token was never registered or already gone."""
    db.query(models.DeviceToken).filter(models.DeviceToken.token == payload.token).delete()
    db.commit()
