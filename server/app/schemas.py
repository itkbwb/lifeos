from __future__ import annotations

from datetime import datetime
from typing import Optional

from pydantic import BaseModel, ConfigDict, field_validator, model_validator

PROJECT_COLORS = {"lavender", "blue", "green", "yellow", "orange", "red", "pink", "gray"}


class ProjectCreate(BaseModel):
    name: str
    color: str

    @field_validator("name")
    @classmethod
    def name_not_blank(cls, v: str) -> str:
        v = v.strip()
        if not v:
            raise ValueError("name must not be empty")
        return v

    @field_validator("color")
    @classmethod
    def color_in_palette(cls, v: str) -> str:
        if v not in PROJECT_COLORS:
            raise ValueError(f"color must be one of {sorted(PROJECT_COLORS)}")
        return v


class ProjectUpdate(BaseModel):
    name: Optional[str] = None
    color: Optional[str] = None
    archived: Optional[bool] = None

    @field_validator("name")
    @classmethod
    def name_not_blank(cls, v: Optional[str]) -> Optional[str]:
        if v is None:
            return v
        v = v.strip()
        if not v:
            raise ValueError("name must not be empty")
        return v

    @field_validator("color")
    @classmethod
    def color_in_palette(cls, v: Optional[str]) -> Optional[str]:
        if v is None:
            return v
        if v not in PROJECT_COLORS:
            raise ValueError(f"color must be one of {sorted(PROJECT_COLORS)}")
        return v


class ProjectOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    name: str
    color: str
    created_at: datetime
    archived: bool


EVENT_TYPES = {"start", "end", "instant"}


class EventCreate(BaseModel):
    project_id: int
    type: str
    occurred_at: Optional[datetime] = None
    label: Optional[str] = None

    @field_validator("type")
    @classmethod
    def type_in_event_types(cls, v: str) -> str:
        if v not in EVENT_TYPES:
            raise ValueError(f"type must be one of {sorted(EVENT_TYPES)}")
        return v


class EventCorrect(BaseModel):
    project_id: Optional[int] = None
    type: Optional[str] = None
    occurred_at: Optional[datetime] = None
    label: Optional[str] = None

    @field_validator("type")
    @classmethod
    def type_in_event_types(cls, v: Optional[str]) -> Optional[str]:
        if v is None:
            return v
        if v not in EVENT_TYPES:
            raise ValueError(f"type must be one of {sorted(EVENT_TYPES)}")
        return v


class EventOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    project_id: int
    type: str
    occurred_at: datetime
    label: Optional[str]
    created_at: datetime
    superseded_by_id: Optional[int]
    corrects_id: Optional[int]
    corrected_at: Optional[datetime]


class ActiveProjectOut(BaseModel):
    project_id: int
    event_id: int
    started_at: datetime


class PlanEntryCreate(BaseModel):
    project_id: int
    start_time: datetime
    end_time: datetime
    name: Optional[str] = None

    @field_validator("end_time")
    @classmethod
    def end_after_start(cls, v: datetime, info) -> datetime:
        start = info.data.get("start_time")
        if start is not None and v <= start:
            raise ValueError("end_time must be after start_time")
        return v

    @field_validator("name")
    @classmethod
    def name_blank_to_none(cls, v: Optional[str]) -> Optional[str]:
        if v is None:
            return v
        v = v.strip()
        return v or None


class PlanEntryOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    project_id: int
    start_time: datetime
    end_time: datetime
    name: Optional[str]
    created_at: datetime


PLAN_CHANGE_TYPES = {"move", "cancel"}


class PlanChangeCreate(BaseModel):
    change_type: str
    new_start_time: Optional[datetime] = None
    new_end_time: Optional[datetime] = None

    @field_validator("change_type")
    @classmethod
    def change_type_in_types(cls, v: str) -> str:
        if v not in PLAN_CHANGE_TYPES:
            raise ValueError(f"change_type must be one of {sorted(PLAN_CHANGE_TYPES)}")
        return v

    @model_validator(mode="after")
    def move_requires_both_times(self) -> "PlanChangeCreate":
        if self.change_type == "move":
            if self.new_start_time is None or self.new_end_time is None:
                raise ValueError("move requires both new_start_time and new_end_time")
            if self.new_end_time <= self.new_start_time:
                raise ValueError("new_end_time must be after new_start_time")
        return self


class PlanChangeOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    plan_entry_id: int
    change_type: str
    new_start_time: Optional[datetime]
    new_end_time: Optional[datetime]
    created_at: datetime


class DynamicPlanEntryOut(BaseModel):
    """A Static PlanEntry with the latest PlanChange (if any) applied.
    Cancelled entries are omitted entirely (never returned by the endpoint).
    """

    id: int
    project_id: int
    start_time: datetime
    end_time: datetime
    name: Optional[str]


class PlanEntryUpdate(BaseModel):
    """Direct mutation of a Static Plan entry (chapter 5.7 - unlike a PlanChange,
    this is a correction to the record itself, used from the Static tab of Day
    Summary). All fields optional/partial; only provided ones are applied."""

    project_id: Optional[int] = None
    start_time: Optional[datetime] = None
    end_time: Optional[datetime] = None
    name: Optional[str] = None

    @field_validator("name")
    @classmethod
    def name_blank_to_none(cls, v: Optional[str]) -> Optional[str]:
        if v is None:
            return v
        v = v.strip()
        return v or None


class ImportRequest(BaseModel):
    csv: str
    tz_offset_minutes: int = 0


class ImportRowError(BaseModel):
    row: int
    message: str


class ImportResult(BaseModel):
    created: int
    projects_created: list[str]
    errors: list[ImportRowError]


CLEAR_SCOPES = {"static", "dynamic", "timeline", "instant", "static_and_dynamic", "all", "projects"}


class ClearRequest(BaseModel):
    """Bulk-wipe a data layer. `dynamic` deletes only PlanChange rows (the
    Static entries they were layered on top of survive, per chapter 5.9);
    `static`/`static_and_dynamic` delete PlanEntry rows, which cascades to
    their PlanChanges too. `timeline` deletes start/end events, `instant`
    deletes instant events. `all` clears every Event and PlanEntry (and,
    via cascade, every PlanChange) but never touches Projects. `projects`
    clears everything `all` does AND every Project too (Events/PlanEntries
    would otherwise RESTRICT the project deletes)."""

    scope: str

    @field_validator("scope")
    @classmethod
    def scope_in_clear_scopes(cls, v: str) -> str:
        if v not in CLEAR_SCOPES:
            raise ValueError(f"scope must be one of {sorted(CLEAR_SCOPES)}")
        return v


class ClearResult(BaseModel):
    deleted_events: int
    deleted_plan_entries: int
    deleted_plan_changes: int
    deleted_projects: int = 0


class DeviceTokenRegister(BaseModel):
    token: str

    @field_validator("token")
    @classmethod
    def token_not_blank(cls, v: str) -> str:
        v = v.strip()
        if not v:
            raise ValueError("token must not be empty")
        return v
