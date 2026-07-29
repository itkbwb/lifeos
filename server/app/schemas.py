from __future__ import annotations

from datetime import datetime
from typing import Optional

from pydantic import BaseModel, ConfigDict, field_validator

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
