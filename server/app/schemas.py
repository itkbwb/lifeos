from datetime import datetime
from typing import Optional

from pydantic import BaseModel, Field, field_validator


def _require_aware(value: datetime) -> datetime:
    if value.tzinfo is None:
        raise ValueError("datetime must include a timezone offset")
    return value


class BlockCreate(BaseModel):
    title: str = Field(min_length=1, max_length=180)
    description: Optional[str] = None
    block_type: str = "work"
    project_id: Optional[int] = None
    planned_start: datetime
    planned_end: datetime

    _validate_start = field_validator("planned_start")(_require_aware)
    _validate_end = field_validator("planned_end")(_require_aware)


class BlockUpdate(BaseModel):
    title: Optional[str] = Field(default=None, min_length=1, max_length=180)
    description: Optional[str] = None
    block_type: Optional[str] = None
    project_id: Optional[int] = None
    planned_start: Optional[datetime] = None
    planned_end: Optional[datetime] = None

    @field_validator("planned_start", "planned_end")
    @classmethod
    def _validate_aware(cls, value: Optional[datetime]) -> Optional[datetime]:
        if value is None:
            return value
        return _require_aware(value)


class RescheduleRequest(BaseModel):
    planned_start: datetime
    planned_end: datetime

    _validate_start = field_validator("planned_start")(_require_aware)
    _validate_end = field_validator("planned_end")(_require_aware)
