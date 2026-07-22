from datetime import datetime, timezone
from zoneinfo import ZoneInfo

from sqlalchemy import DateTime
from sqlalchemy.types import TypeDecorator

APP_TZ = ZoneInfo("Asia/Seoul")


class UTCDateTime(TypeDecorator):
    """Stores aware datetimes as naive UTC; returns them as aware UTC.

    Guards against accidentally persisting a naive (ambiguous-timezone)
    value, which is the exact bug class this column type exists to prevent.
    """

    impl = DateTime
    cache_ok = True

    def process_bind_param(self, value, dialect):
        if value is None:
            return None
        if value.tzinfo is None:
            raise ValueError(
                "UTCDateTime received a naive datetime; all datetimes must be "
                "timezone-aware before they reach the database layer"
            )
        return value.astimezone(timezone.utc).replace(tzinfo=None)

    def process_result_value(self, value, dialect):
        if value is None:
            return None
        return value.replace(tzinfo=timezone.utc)


def now_utc() -> datetime:
    """Aware current instant in UTC."""
    return datetime.now(timezone.utc)


def to_utc_naive(dt: datetime) -> datetime:
    """Convert an aware datetime to naive UTC for storage in SQLite."""
    if dt.tzinfo is None:
        raise ValueError("to_utc_naive requires an aware datetime")
    return dt.astimezone(timezone.utc).replace(tzinfo=None)


def from_utc_naive(dt: datetime) -> datetime:
    """Attach UTC tzinfo to a naive datetime read back from SQLite."""
    if dt.tzinfo is not None:
        raise ValueError("from_utc_naive requires a naive datetime")
    return dt.replace(tzinfo=timezone.utc)


def to_local(dt: datetime, tz_name: str = "Asia/Seoul") -> datetime:
    """Convert an aware datetime to the given local timezone for display."""
    return dt.astimezone(ZoneInfo(tz_name))
