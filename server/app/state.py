from __future__ import annotations

from datetime import datetime, timedelta

from .models import ScheduleBlock
from .status import BlockStatus

OVERDUE_THRESHOLD_MINUTES = 15


def effective_status(block: ScheduleBlock, now: datetime) -> str:
    """Derived display status. Never written back to block.status - the
    stored value only ever changes via an explicit state_machine transition."""
    if block.status == BlockStatus.PLANNED.value and now >= block.planned_start:
        return "ready"
    return block.status


def is_overdue(block: ScheduleBlock, effective: str, now: datetime) -> bool:
    if effective not in {"ready", "active", "paused"}:
        return False
    return now >= block.planned_end + timedelta(minutes=OVERDUE_THRESHOLD_MINUTES)
