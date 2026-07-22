from __future__ import annotations

from datetime import date, datetime, time, timedelta

from sqlalchemy import select

from .database import SessionLocal
from .models import AppSettings, Project, ScheduleBlock, User
from .status import BlockStatus
from .tz import APP_TZ, now_utc

PLAN_END_DATE = date(2026, 8, 15)
DEFAULT_USER_EMAIL = "owner@life-os.local"

DEFAULT_PROJECTS = [
    ("Нанопровода", 100, "science"),
    ("Статья Сонъёля", 90, "science"),
    ("InAs droplet etch", 80, "science"),
    ("Подготовка к Германии", 85, "travel"),
    ("Инфраструктура", 50, "infrastructure"),
    ("Домашний сервер", 30, "infrastructure"),
    ("Быт", 40, "life"),
    ("Здоровье", 90, "health"),
    ("Сон", 100, "recovery"),
]


def _project_rotation(day: date) -> tuple[str, str, str, str]:
    weekday = day.weekday()

    rotations = {
        0: ("Нанопровода", "Статья Сонъёля", "InAs droplet etch", "Инфраструктура"),
        1: ("Нанопровода", "InAs droplet etch", "Статья Сонъёля", "Подготовка к Германии"),
        2: ("Нанопровода", "Статья Сонъёля", "InAs droplet etch", "Инфраструктура"),
        3: ("Нанопровода", "InAs droplet etch", "Статья Сонъёля", "Подготовка к Германии"),
        4: ("Нанопровода", "Статья Сонъёля", "InAs droplet etch", "Инфраструктура"),
        5: ("InAs droplet etch", "Подготовка к Германии", "Быт", "Домашний сервер"),
        6: ("Нанопровода", "Статья Сонъёля", "Подготовка к Германии", "Инфраструктура"),
    }
    return rotations[weekday]


def _day_template(day: date) -> list[tuple[time, time, str, str, str | None]]:
    primary, secondary, tertiary, evening = _project_rotation(day)
    gym_day = day.weekday() != 5

    blocks: list[tuple[time, time, str, str, str | None]] = [
        (time(7, 0), time(7, 50), "Утренняя рутина", "routine", None),
        (time(7, 50), time(8, 20), "Дорога в институт", "travel", None),
        (time(8, 20), time(11, 50), primary, "work", primary),
        (time(11, 50), time(12, 20), "Прием пищи 2", "meal", None),
        (time(12, 20), time(15, 20), secondary, "work", secondary),
        (time(15, 20), time(15, 40), "Прием пищи 3", "meal", None),
        (time(15, 40), time(18, 20), tertiary, "work", tertiary),
    ]

    if gym_day:
        blocks.extend(
            [
                (time(18, 20), time(19, 50), "Зал", "health", "Здоровье"),
                (time(19, 50), time(20, 20), "Душ и прием пищи 4", "meal", "Здоровье"),
            ]
        )
    else:
        blocks.extend(
            [
                (time(18, 20), time(19, 20), "Стирка, уборка, закупки", "life", "Быт"),
                (time(19, 20), time(20, 20), "Душ и прием пищи 4", "meal", "Здоровье"),
            ]
        )

    blocks.extend(
        [
            (time(20, 20), time(22, 50), evening, "work", evening),
            (time(22, 50), time(23, 10), "Дорога домой", "travel", None),
            (time(23, 10), time(23, 50), "Вечерняя рутина", "routine", None),
            # Crosses midnight: sleep runs until next morning, not a stub
            # ending at 23:59 the same day (the old known bug).
            (time(23, 50), time(7, 0), "Сон", "recovery", "Сон"),
        ]
    )
    return blocks


def _local_dt(day: date, t: time) -> datetime:
    return datetime.combine(day, t, tzinfo=APP_TZ)


def _ensure_user(db) -> User:
    user = db.scalar(select(User).where(User.email == DEFAULT_USER_EMAIL))
    if user is None:
        now = now_utc()
        user = User(email=DEFAULT_USER_EMAIL, created_at=now)
        db.add(user)
        db.flush()
        db.add(
            AppSettings(
                user_id=user.id,
                timezone="Asia/Seoul",
                locale="ru",
                day_start_time=time(5, 0),
                created_at=now,
                updated_at=now,
            )
        )
    return user


def ensure_seed_data() -> None:
    with SessionLocal() as db:
        now = now_utc()
        user = _ensure_user(db)

        projects_by_name: dict[str, Project] = {}
        for name, priority, category in DEFAULT_PROJECTS:
            project = db.scalar(
                select(Project).where(Project.user_id == user.id, Project.name == name)
            )
            if project is None:
                project = Project(user_id=user.id, name=name, priority=priority, category=category)
                db.add(project)
                db.flush()
            else:
                project.priority = priority
                project.category = category
            projects_by_name[name] = project

        start = now.astimezone(APP_TZ).date()
        end = max(start + timedelta(days=13), PLAN_END_DATE)
        current = start

        while current <= end:
            has_date = db.scalar(
                select(ScheduleBlock.id)
                .where(
                    ScheduleBlock.user_id == user.id,
                    ScheduleBlock.planned_start >= _local_dt(current, time(0, 0)),
                    ScheduleBlock.planned_start < _local_dt(current + timedelta(days=1), time(0, 0)),
                )
                .limit(1)
            )
            if has_date is None:
                for start_time, end_time, title, block_type, project_name in _day_template(current):
                    project = projects_by_name.get(project_name) if project_name else None
                    planned_start = _local_dt(current, start_time)
                    end_day = current + timedelta(days=1) if end_time <= start_time else current
                    planned_end = _local_dt(end_day, end_time)
                    db.add(
                        ScheduleBlock(
                            user_id=user.id,
                            project_id=project.id if project else None,
                            block_type=block_type,
                            title=title,
                            planned_start=planned_start,
                            planned_end=planned_end,
                            status=BlockStatus.PLANNED.value,
                            created_at=now,
                            updated_at=now,
                        )
                    )
            current += timedelta(days=1)

        db.commit()
