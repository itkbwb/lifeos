from __future__ import annotations

import logging
from datetime import datetime, timedelta, timezone

from apscheduler.schedulers.background import BackgroundScheduler

from app import models, notifications
from app.database import SessionLocal

logger = logging.getLogger("lifeos.scheduler")

# The server isn't constrained by WorkManager's 15-minute platform minimum
# (see the Android-side PlanNotificationWorker this replaced) - a short
# server-side interval plus real FCM push is what makes this feel instant.
CHECK_INTERVAL_SECONDS = 60
START_WINDOW = timedelta(minutes=20)

_scheduler: BackgroundScheduler | None = None


def _get_state(db, key: str) -> int:
    row = db.get(models.NotificationState, key)
    return row.value if row is not None else -1


def _set_state(db, key: str, value: int) -> None:
    row = db.get(models.NotificationState, key)
    if row is None:
        db.add(models.NotificationState(key=key, value=value))
    else:
        row.value = value
    db.commit()


def check_and_notify() -> None:
    """Server-side equivalent of chapter: notifications' two suggestions -
    moved here (from the Android client's periodic WorkManager check) so the
    server, not the phone, decides when to push, and delivery is real FCM
    push rather than a client polling loop that Android's Doze/App Standby
    can defer for hours."""
    from app import main as main_module  # local import: avoids a circular import at module load

    db = SessionLocal()
    try:
        now = datetime.now(timezone.utc)
        today_start = now.replace(hour=0, minute=0, second=0, microsecond=0)
        today_end = today_start + timedelta(days=1)

        dynamic_plan = main_module.get_dynamic_plan(project_id=None, from_=today_start, to=today_end, db=db)
        active = main_module.get_active_start(db)
        projects_by_id = {p.id: p for p in db.query(models.Project).all()}

        window_end = now + START_WINDOW
        start_candidate = next(
            (
                entry
                for entry in dynamic_plan
                if entry.start_time >= now
                and entry.start_time < window_end
                and (active is None or active.project_id != entry.project_id)
            ),
            None,
        )
        if start_candidate is not None:
            last_start = _get_state(db, "last_start_notified_plan_entry_id")
            if start_candidate.id != last_start:
                project_name = projects_by_id.get(start_candidate.project_id)
                project_name = project_name.name if project_name else "проект"
                sent = notifications.send_push(
                    title=f"Начать «{project_name}»?",
                    body="Скоро время по плану",
                    data={
                        "type": "start",
                        "project_id": start_candidate.project_id,
                        "project_name": project_name,
                    },
                )
                if sent > 0:
                    _set_state(db, "last_start_notified_plan_entry_id", start_candidate.id)

        if active is not None:
            active_plan = next((e for e in dynamic_plan if e.project_id == active.project_id), None)
            if active_plan is not None and active_plan.end_time < now:
                last_stop = _get_state(db, "last_stop_notified_event_id")
                if active.id != last_stop:
                    project_name = projects_by_id.get(active.project_id)
                    project_name = project_name.name if project_name else "проект"
                    sent = notifications.send_push(
                        title=f"Закончить «{project_name}»?",
                        body="Запланированное время истекло",
                        data={
                            "type": "end",
                            "project_id": active.project_id,
                            "project_name": project_name,
                        },
                    )
                    if sent > 0:
                        _set_state(db, "last_stop_notified_event_id", active.id)
    except Exception:  # noqa: BLE001 - a failed tick must never kill the scheduler thread
        logger.exception("plan notification check failed")
    finally:
        db.close()


def start() -> None:
    """No-ops if FCM isn't configured (see notifications.ensure_firebase) -
    keeps local/dev/test/CI runs unaffected until a Firebase project exists."""
    global _scheduler
    if _scheduler is not None:
        return
    if notifications.ensure_firebase() is None:
        return
    _scheduler = BackgroundScheduler()
    _scheduler.add_job(check_and_notify, "interval", seconds=CHECK_INTERVAL_SECONDS, id="plan_notifications")
    _scheduler.start()
