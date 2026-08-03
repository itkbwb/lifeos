from __future__ import annotations

from datetime import date, datetime, time
from typing import Optional
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from pydantic import BaseModel, ConfigDict, field_validator, model_validator

PROJECT_COLORS = {"lavender", "blue", "green", "yellow", "orange", "red", "pink", "gray"}
RECURRENCE_FREQUENCIES = {"daily", "weekly", "monthly", "yearly"}
MONTH_MODES = {"day_of_month", "weekday_of_month"}


def _validate_time_of_day(v: str) -> str:
    try:
        time.fromisoformat(v)
    except ValueError as exc:
        raise ValueError("must be a HH:MM time") from exc
    return v


def _validate_weekdays(v: str) -> str:
    try:
        nums = {int(p) for p in v.split(",")}
    except ValueError as exc:
        raise ValueError("weekdays must be comma-separated integers 1-7 (Mon=1..Sun=7)") from exc
    if not nums or not nums.issubset(set(range(1, 8))):
        raise ValueError("weekdays must be comma-separated integers 1-7 (Mon=1..Sun=7)")
    return ",".join(str(n) for n in sorted(nums))


def _validate_timezone(v: str) -> str:
    try:
        ZoneInfo(v)
    except ZoneInfoNotFoundError as exc:
        raise ValueError("unknown IANA timezone") from exc
    return v


def _validate_frequency(v: str) -> str:
    if v not in RECURRENCE_FREQUENCIES:
        raise ValueError(f"frequency must be one of {sorted(RECURRENCE_FREQUENCIES)}")
    return v


def _validate_interval(v: int) -> int:
    if v < 1:
        raise ValueError("interval must be >= 1")
    return v


def _validate_month_mode(v: str) -> str:
    if v not in MONTH_MODES:
        raise ValueError(f"month_mode must be one of {sorted(MONTH_MODES)}")
    return v


def _validate_max_occurrences(v: int) -> int:
    if v < 1:
        raise ValueError("max_occurrences must be >= 1")
    return v


class ProjectCreate(BaseModel):
    name: str
    color: str
    notes: Optional[str] = None

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
    notes: Optional[str] = None

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
    notes: Optional[str]


class ProjectMergeRequest(BaseModel):
    """Folds `source_id` into `target_id` (chapter: archive name-collision
    resolution) - see `merge_projects` for exactly what gets reassigned."""

    source_id: int
    target_id: int


class ProjectMergeResult(BaseModel):
    target_project_id: int
    subtasks_moved: int
    events_moved: int
    plan_entries_moved: int


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
    label: Optional[str] = None


class ReminderCreate(BaseModel):
    remind_at: datetime
    message: str

    @field_validator("message")
    @classmethod
    def message_not_blank(cls, v: str) -> str:
        v = v.strip()
        if not v:
            raise ValueError("message must not be empty")
        return v


class ReminderOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    remind_at: datetime
    message: str
    notified: bool
    created_at: datetime


class PlanEntryCreate(BaseModel):
    project_id: int
    start_time: datetime
    end_time: datetime
    name: Optional[str] = None
    subtask_id: Optional[int] = None

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
    subtask_id: Optional[int]
    recurring_plan_id: Optional[int]
    created_at: datetime


class RecurringPlanCreate(BaseModel):
    project_id: int
    subtask_id: Optional[int] = None
    name: Optional[str] = None
    start_time_of_day: str
    end_time_of_day: str
    frequency: str = "weekly"
    interval: int = 1
    # Required (non-empty) when frequency == "weekly"; ignored otherwise.
    weekdays: Optional[str] = None
    # Required when frequency == "monthly" (defaults to "day_of_month" if omitted); ignored
    # otherwise.
    month_mode: Optional[str] = None
    max_occurrences: Optional[int] = None
    timezone: str
    series_start_date: date
    series_end_date: Optional[date] = None

    @field_validator("start_time_of_day", "end_time_of_day")
    @classmethod
    def valid_time_of_day(cls, v: str) -> str:
        return _validate_time_of_day(v)

    @field_validator("frequency")
    @classmethod
    def valid_frequency(cls, v: str) -> str:
        return _validate_frequency(v)

    @field_validator("interval")
    @classmethod
    def valid_interval(cls, v: int) -> int:
        return _validate_interval(v)

    @field_validator("weekdays")
    @classmethod
    def valid_weekdays(cls, v: Optional[str]) -> Optional[str]:
        return v if v is None else _validate_weekdays(v)

    @field_validator("month_mode")
    @classmethod
    def valid_month_mode(cls, v: Optional[str]) -> Optional[str]:
        return v if v is None else _validate_month_mode(v)

    @field_validator("max_occurrences")
    @classmethod
    def valid_max_occurrences(cls, v: Optional[int]) -> Optional[int]:
        return v if v is None else _validate_max_occurrences(v)

    @field_validator("timezone")
    @classmethod
    def valid_timezone(cls, v: str) -> str:
        return _validate_timezone(v)

    @field_validator("name")
    @classmethod
    def name_blank_to_none(cls, v: Optional[str]) -> Optional[str]:
        if v is None:
            return v
        v = v.strip()
        return v or None

    @model_validator(mode="after")
    def frequency_specific_requirements(self) -> "RecurringPlanCreate":
        if time.fromisoformat(self.end_time_of_day) <= time.fromisoformat(self.start_time_of_day):
            raise ValueError("end_time_of_day must be after start_time_of_day")
        if self.series_end_date is not None and self.series_end_date < self.series_start_date:
            raise ValueError("series_end_date must not be before series_start_date")
        if self.frequency == "weekly" and not self.weekdays:
            raise ValueError("weekly frequency requires weekdays")
        if self.frequency == "monthly" and self.month_mode is None:
            self.month_mode = "day_of_month"
        return self


class RecurringPlanUpdate(BaseModel):
    """Partial update for the "all occurrences" edit (chapter: recurring plans) - applied to
    the series template, then the caller regenerates today-forward occurrences under the new
    values (see app/recurrence.py). `timezone`/`series_start_date` are intentionally not
    editable here - retroactively changing either is out of scope, delete+recreate instead."""

    project_id: Optional[int] = None
    subtask_id: Optional[int] = None
    name: Optional[str] = None
    start_time_of_day: Optional[str] = None
    end_time_of_day: Optional[str] = None
    frequency: Optional[str] = None
    interval: Optional[int] = None
    weekdays: Optional[str] = None
    month_mode: Optional[str] = None
    max_occurrences: Optional[int] = None
    series_end_date: Optional[date] = None

    @field_validator("start_time_of_day", "end_time_of_day")
    @classmethod
    def valid_time_of_day(cls, v: Optional[str]) -> Optional[str]:
        return v if v is None else _validate_time_of_day(v)

    @field_validator("frequency")
    @classmethod
    def valid_frequency(cls, v: Optional[str]) -> Optional[str]:
        return v if v is None else _validate_frequency(v)

    @field_validator("interval")
    @classmethod
    def valid_interval(cls, v: Optional[int]) -> Optional[int]:
        return v if v is None else _validate_interval(v)

    @field_validator("weekdays")
    @classmethod
    def valid_weekdays(cls, v: Optional[str]) -> Optional[str]:
        return v if v is None else _validate_weekdays(v)

    @field_validator("month_mode")
    @classmethod
    def valid_month_mode(cls, v: Optional[str]) -> Optional[str]:
        return v if v is None else _validate_month_mode(v)

    @field_validator("max_occurrences")
    @classmethod
    def valid_max_occurrences(cls, v: Optional[int]) -> Optional[int]:
        return v if v is None else _validate_max_occurrences(v)

    @field_validator("name")
    @classmethod
    def name_blank_to_none(cls, v: Optional[str]) -> Optional[str]:
        if v is None:
            return v
        v = v.strip()
        return v or None


class RecurringPlanOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    project_id: int
    subtask_id: Optional[int]
    name: Optional[str]
    start_time_of_day: str
    end_time_of_day: str
    frequency: str
    interval: int
    weekdays: str
    month_mode: Optional[str]
    max_occurrences: Optional[int]
    timezone: str
    series_start_date: date
    series_end_date: Optional[date]
    created_at: datetime


class RecurrenceSplitRequest(BaseModel):
    """Body for POST /api/plan/entries/{entry_id}/recurrence/split ("this and following"
    edit, chapter: recurring plans) - ends the original series the day before this
    occurrence's date and starts a new series from that date with these values. Timezone and
    any pattern field left unset (None) are inherited from the original series - the quick
    "this and following" edit from the Day Summary usually only changes time/project/name."""

    project_id: int
    subtask_id: Optional[int] = None
    name: Optional[str] = None
    start_time_of_day: str
    end_time_of_day: str
    frequency: Optional[str] = None
    interval: Optional[int] = None
    weekdays: Optional[str] = None
    month_mode: Optional[str] = None
    max_occurrences: Optional[int] = None
    series_end_date: Optional[date] = None

    @field_validator("start_time_of_day", "end_time_of_day")
    @classmethod
    def valid_time_of_day(cls, v: str) -> str:
        return _validate_time_of_day(v)

    @field_validator("frequency")
    @classmethod
    def valid_frequency(cls, v: Optional[str]) -> Optional[str]:
        return v if v is None else _validate_frequency(v)

    @field_validator("interval")
    @classmethod
    def valid_interval(cls, v: Optional[int]) -> Optional[int]:
        return v if v is None else _validate_interval(v)

    @field_validator("weekdays")
    @classmethod
    def valid_weekdays(cls, v: Optional[str]) -> Optional[str]:
        return v if v is None else _validate_weekdays(v)

    @field_validator("month_mode")
    @classmethod
    def valid_month_mode(cls, v: Optional[str]) -> Optional[str]:
        return v if v is None else _validate_month_mode(v)

    @field_validator("max_occurrences")
    @classmethod
    def valid_max_occurrences(cls, v: Optional[int]) -> Optional[int]:
        return v if v is None else _validate_max_occurrences(v)

    @field_validator("name")
    @classmethod
    def name_blank_to_none(cls, v: Optional[str]) -> Optional[str]:
        if v is None:
            return v
        v = v.strip()
        return v or None

    @model_validator(mode="after")
    def end_after_start(self) -> "RecurrenceSplitRequest":
        if time.fromisoformat(self.end_time_of_day) <= time.fromisoformat(self.start_time_of_day):
            raise ValueError("end_time_of_day must be after start_time_of_day")
        return self


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
    subtask_id: Optional[int]


class PlanEntryUpdate(BaseModel):
    """Direct mutation of a Static Plan entry (chapter 5.7 - unlike a PlanChange,
    this is a correction to the record itself, used from the Static tab of Day
    Summary). All fields optional/partial; only provided ones are applied.

    `subtask_id` is the one field that needs explicit-null ("unlink") support -
    the endpoint checks `model_fields_set` for it rather than treating `None`
    as "not provided", so a client can send `{"subtask_id": null}` to clear
    the link (a bare-omitted field still means "don't change" for every field,
    including this one)."""

    project_id: Optional[int] = None
    start_time: Optional[datetime] = None
    end_time: Optional[datetime] = None
    name: Optional[str] = None
    subtask_id: Optional[int] = None

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


class ImportSubtask(BaseModel):
    """A task in the import file's checklist tree - top-level entries (chapter:
    Задача) and their nested entries (chapter: Подзадача, any depth) share this
    same shape; `subtasks` is this node's own children. Self-referential model -
    works with no extra ceremony under Pydantic v2 + this module's
    `from __future__ import annotations`."""

    title: str
    done: bool = False
    is_checklist: bool = False
    subtasks: list[ImportSubtask] = []

    @field_validator("title")
    @classmethod
    def title_not_blank(cls, v: str) -> str:
        v = v.strip()
        if not v:
            raise ValueError("title must not be empty")
        return v


class ImportStaticEntry(BaseModel):
    date: str
    start: str
    end: str
    name: Optional[str] = None
    subtask_title: Optional[str] = None


class ImportProjectRequest(BaseModel):
    """A whole-project export/import file (chapter: project import) - project
    identity, its checklist, and optionally pre-scheduled Static entries that
    can reference a checklist item by title. Distinct from the flat CSV
    importer above, which only ever creates Static entries."""

    project_name: str
    color: Optional[str] = None
    subtasks: list[ImportSubtask] = []
    static_entries: list[ImportStaticEntry] = []
    tz_offset_minutes: int = 0

    @field_validator("project_name")
    @classmethod
    def project_name_not_blank(cls, v: str) -> str:
        v = v.strip()
        if not v:
            raise ValueError("project_name must not be empty")
        return v

    @field_validator("color")
    @classmethod
    def color_in_palette(cls, v: Optional[str]) -> Optional[str]:
        if v is None:
            return v
        if v not in PROJECT_COLORS:
            raise ValueError(f"color must be one of {sorted(PROJECT_COLORS)}")
        return v


class ImportProjectResult(BaseModel):
    project_id: int
    project_created: bool
    subtasks_created: int
    subtasks_skipped: int
    static_entries_created: int
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


class SubtaskCreate(BaseModel):
    project_id: int
    title: str
    parent_id: Optional[int] = None
    is_checklist: bool = False

    @field_validator("title")
    @classmethod
    def title_not_blank(cls, v: str) -> str:
        v = v.strip()
        if not v:
            raise ValueError("title must not be empty")
        return v


class SubtaskUpdate(BaseModel):
    """`parent_id` is the one field here that needs explicit-null ("move to
    root") support - the endpoint checks `model_fields_set` for it, same
    pattern as `PlanEntryUpdate.subtask_id`, so a bare-omitted field still
    means "don't change" while `{"parent_id": null}` outdents to the root."""

    title: Optional[str] = None
    done: Optional[bool] = None
    notes: Optional[str] = None
    parent_id: Optional[int] = None

    @field_validator("title")
    @classmethod
    def title_not_blank(cls, v: Optional[str]) -> Optional[str]:
        if v is None:
            return v
        v = v.strip()
        if not v:
            raise ValueError("title must not be empty")
        return v


class SubtaskOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    project_id: int
    parent_id: Optional[int]
    title: str
    done: bool
    position: int
    notes: Optional[str]
    is_checklist: bool
    instant_event_id: Optional[int]
    created_at: datetime


class SubtaskReorder(BaseModel):
    """The ordered ids of one sibling group - all subtasks sharing the same
    `parent_id` (None = the project's root-level subtasks) - must be exactly
    that group's current id set (no partial reorders, nothing dropped, and
    reordering one group never touches another parent's children)."""

    ordered_ids: list[int]
    parent_id: Optional[int] = None


class DeviceTokenRegister(BaseModel):
    token: str

    @field_validator("token")
    @classmethod
    def token_not_blank(cls, v: str) -> str:
        v = v.strip()
        if not v:
            raise ValueError("token must not be empty")
        return v
