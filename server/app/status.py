from enum import Enum


class BlockStatus(str, Enum):
    """Stored states only. `ready` and `overdue` are derived at read time
    and must never be written to ScheduleBlock.status - see app/state.py."""

    PLANNED = "planned"
    ACTIVE = "active"
    PAUSED = "paused"
    COMPLETED = "completed"
    SKIPPED = "skipped"
    RESCHEDULED = "rescheduled"
    CANCELLED = "cancelled"


class WorkSessionStatus(str, Enum):
    ACTIVE = "active"
    PAUSED = "paused"
    COMPLETED = "completed"
