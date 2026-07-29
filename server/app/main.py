from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path

from fastapi import Depends, FastAPI, HTTPException
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
    db.commit()
