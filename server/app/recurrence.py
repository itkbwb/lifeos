from __future__ import annotations

import calendar
from datetime import date, datetime, time, timedelta, timezone
from typing import Iterator, Optional
from zoneinfo import ZoneInfo

from sqlalchemy.orm import Session

from app import models

# Static Plan occurrences are only ever materialized this far ahead - same "don't plan into
# infinity" behavior as Google Calendar recurring events. `sync_all` re-runs periodically (see
# app/scheduler.py) so the window keeps rolling forward as real time passes.
GENERATION_HORIZON_DAYS = 30


def _local_midnight_utc(d: date, tz: ZoneInfo) -> datetime:
    return datetime.combine(d, time.min, tzinfo=tz).astimezone(timezone.utc)


def _nth_weekday_of_month(year: int, month: int, iso_weekday: int, ordinal: int) -> Optional[date]:
    """`ordinal`: 1-5 for 1st..5th occurrence of `iso_weekday` in the month, or -1 for "last"."""
    days_in_month = calendar.monthrange(year, month)[1]
    matches = [d for d in range(1, days_in_month + 1) if date(year, month, d).isoweekday() == iso_weekday]
    if ordinal == -1:
        return date(year, month, matches[-1]) if matches else None
    if 1 <= ordinal <= len(matches):
        return date(year, month, matches[ordinal - 1])
    return None


def _same_day_of_month(year: int, month: int, day: int) -> Optional[date]:
    days_in_month = calendar.monthrange(year, month)[1]
    return date(year, month, day) if day <= days_in_month else None


def _same_month_day(year: int, month: int, day: int) -> Optional[date]:
    try:
        return date(year, month, day)
    except ValueError:  # Feb 29 on a non-leap year
        return None


def _candidate_dates(plan: models.RecurringPlan, scan_start: date, scan_end: date) -> Iterator[date]:
    """Every date the series occurs on, from `scan_start` (warm-started at-or-after
    `plan.series_start_date`) through `scan_end` inclusive - a simplified RRULE covering the
    same presets Google Calendar's own recurrence picker exposes (chapter: recurring plans).
    """
    start = plan.series_start_date
    interval = max(1, plan.interval)
    scan_start = max(scan_start, start)
    if scan_start > scan_end:
        return

    if plan.frequency == "daily":
        offset = (scan_start - start).days
        first_offset = offset if offset % interval == 0 else offset + (interval - offset % interval)
        cur = start + timedelta(days=first_offset)
        while cur <= scan_end:
            yield cur
            cur += timedelta(days=interval)

    elif plan.frequency == "weekly":
        weekdays = {int(d) for d in plan.weekdays.split(",") if d} if plan.weekdays else set()
        week_start_of_series = start - timedelta(days=start.isoweekday() - 1)
        cur = scan_start
        while cur <= scan_end:
            week_start_of_cur = cur - timedelta(days=cur.isoweekday() - 1)
            weeks_diff = (week_start_of_cur - week_start_of_series).days // 7
            if weeks_diff % interval == 0 and cur.isoweekday() in weekdays:
                yield cur
            cur += timedelta(days=1)

    elif plan.frequency == "monthly":
        weekday_mode = plan.month_mode == "weekday_of_month"
        start_weekday = start.isoweekday()
        days_in_start_month = calendar.monthrange(start.year, start.month)[1]
        ordinal = -1 if (start.day + 7) > days_in_start_month else ((start.day - 1) // 7) + 1

        y, m = scan_start.year, scan_start.month
        while date(y, m, 1) <= scan_end:
            months_diff = (y - start.year) * 12 + (m - start.month)
            if months_diff % interval == 0:
                candidate = (
                    _nth_weekday_of_month(y, m, start_weekday, ordinal)
                    if weekday_mode
                    else _same_day_of_month(y, m, start.day)
                )
                if candidate is not None and scan_start <= candidate <= scan_end:
                    yield candidate
            m += 1
            if m > 12:
                m = 1
                y += 1

    else:  # yearly
        y = scan_start.year
        while date(y, 1, 1) <= scan_end:
            years_diff = y - start.year
            if years_diff % interval == 0:
                candidate = _same_month_day(y, start.month, start.day)
                if candidate is not None and scan_start <= candidate <= scan_end:
                    yield candidate
            y += 1


def generate_occurrences(
    db: Session, plan: models.RecurringPlan, horizon_days: int = GENERATION_HORIZON_DAYS
) -> list[models.PlanEntry]:
    """Materializes any missing PlanEntry rows for `plan` between today and the rolling
    horizon (chapter: recurring plans). Idempotent - dates that already have a PlanEntry
    (or a RecurringPlanException marking them deliberately skipped) are left alone, so this
    is safe to call on every scheduler tick and immediately after create/update.

    `max_occurrences` (if set) is counted from `plan.series_start_date`, not from today - an
    occurrence that already elapsed before this series was ever synced still counts toward the
    limit, matching what a user who picked "after 10 times" would expect."""
    tz = ZoneInfo(plan.timezone)
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
    for index, candidate in enumerate(_candidate_dates(plan, plan.series_start_date, range_end), start=1):
        if plan.max_occurrences is not None and index > plan.max_occurrences:
            break
        if candidate < range_start or candidate in existing_dates or candidate in exception_dates:
            continue
        entry = models.PlanEntry(
            project_id=plan.project_id,
            start_time=datetime.combine(candidate, start_time_of_day, tzinfo=tz).astimezone(timezone.utc),
            end_time=datetime.combine(candidate, end_time_of_day, tzinfo=tz).astimezone(timezone.utc),
            name=plan.name,
            subtask_id=plan.subtask_id,
            recurring_plan_id=plan.id,
        )
        db.add(entry)
        created.append(entry)

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
