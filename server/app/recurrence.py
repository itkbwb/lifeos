from __future__ import annotations

from datetime import date, datetime, time, timedelta, timezone
from zoneinfo import ZoneInfo

from sqlalchemy.orm import Session

from app import models

# Static Plan occurrences are only ever materialized this far ahead - same "don't plan into
# infinity" behavior as Google Calendar recurring events. `sync_all` re-runs periodically (see
# app/scheduler.py) so the window keeps rolling forward as real time passes.
GENERATION_HORIZON_DAYS = 30


def _local_midnight_utc(d: date, tz: ZoneInfo) -> datetime:
    return datetime.combine(d, time.min, tzinfo=tz).astimezone(timezone.utc)


def generate_occurrences(
    db: Session, plan: models.RecurringPlan, horizon_days: int = GENERATION_HORIZON_DAYS
) -> list[models.PlanEntry]:
    """Materializes any missing PlanEntry rows for `plan` between today and the rolling
    horizon (chapter: recurring plans). Idempotent - dates that already have a PlanEntry
    (or a RecurringPlanException marking them deliberately skipped) are left alone, so this
    is safe to call on every scheduler tick and immediately after create/update."""
    tz = ZoneInfo(plan.timezone)
    weekdays = {int(d) for d in plan.weekdays.split(",")}
    start_time_of_day = time.fromisoformat(plan.start_time_of_day)
    end_time_of_day = time.fromisoformat(plan.end_time_of_day)

    today_local = datetime.now(tz).date()
    range_start = max(plan.series_start_date, today_local)
    # horizon_days total days INCLUDING today, matching "generate N days ahead" (today is day 1).
    range_end = today_local + timedelta(days=horizon_days - 1)
    if plan.series_end_date is not None:
        range_end = min(range_end, plan.series_end_date)
    if range_end < range_start:
        return []

    existing_dates = {
        e.start_time.astimezone(tz).date()
        for e in db.query(models.PlanEntry)
        .filter(models.PlanEntry.recurring_plan_id == plan.id)
        .all()
    }
    exception_dates = {
        e.date
        for e in db.query(models.RecurringPlanException)
        .filter(models.RecurringPlanException.recurring_plan_id == plan.id)
        .all()
    }

    created: list[models.PlanEntry] = []
    cur = range_start
    while cur <= range_end:
        if cur.isoweekday() in weekdays and cur not in existing_dates and cur not in exception_dates:
            entry = models.PlanEntry(
                project_id=plan.project_id,
                start_time=datetime.combine(cur, start_time_of_day, tzinfo=tz).astimezone(timezone.utc),
                end_time=datetime.combine(cur, end_time_of_day, tzinfo=tz).astimezone(timezone.utc),
                name=plan.name,
                subtask_id=plan.subtask_id,
                recurring_plan_id=plan.id,
            )
            db.add(entry)
            created.append(entry)
        cur += timedelta(days=1)

    if created:
        db.commit()
        for entry in created:
            db.refresh(entry)
    return created


def sync_all(db: Session, horizon_days: int = GENERATION_HORIZON_DAYS) -> None:
    for plan in db.query(models.RecurringPlan).all():
        generate_occurrences(db, plan, horizon_days)


def delete_future_occurrences(db: Session, plan: models.RecurringPlan, from_date: date) -> None:
    """Removes materialized occurrences of `plan` from `from_date` (inclusive) onward -
    used when a series is edited/stopped "this and following"/"all", so stale generated
    entries under the old template don't linger alongside freshly regenerated ones."""
    tz = ZoneInfo(plan.timezone)
    cutoff = _local_midnight_utc(from_date, tz)
    db.query(models.PlanEntry).filter(
        models.PlanEntry.recurring_plan_id == plan.id,
        models.PlanEntry.start_time >= cutoff,
    ).delete(synchronize_session=False)
    db.commit()
    # synchronize_session=False leaves the session's identity map holding stale in-memory
    # objects for the just-deleted rows - without this, SQLite's rowid reuse can make a
    # freshly-generated replacement row collide with one of those stale objects (harmless, but
    # noisy "identity map already had an identity" warnings and needless staleness on `plan`
    # itself, which callers keep using right after this call).
    db.expire_all()
