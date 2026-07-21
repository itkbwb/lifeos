from datetime import date, time
from typing import Optional

from pydantic import BaseModel, Field


class BlockCreate(BaseModel):
    block_date: date
    start_time: time
    end_time: time
    title: str = Field(min_length=1, max_length=180)
    notes: Optional[str] = None
    block_type: str = "work"
    project_id: Optional[int] = None


class BlockUpdate(BaseModel):
    block_date: Optional[date] = None
    start_time: Optional[time] = None
    end_time: Optional[time] = None
    title: Optional[str] = Field(default=None, min_length=1, max_length=180)
    notes: Optional[str] = None
    status: Optional[str] = None
    block_type: Optional[str] = None
    project_id: Optional[int] = None
