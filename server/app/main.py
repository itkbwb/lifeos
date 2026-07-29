from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path

from fastapi import FastAPI

BASE_DIR = Path(__file__).resolve().parent
VERSION = (BASE_DIR.parent / "VERSION").read_text(encoding="utf-8").strip()

app = FastAPI(title="Life OS", version=VERSION)


@app.get("/health")
def health():
    return {
        "status": "ok",
        "version": VERSION,
        "time": datetime.now(timezone.utc).isoformat(),
    }
