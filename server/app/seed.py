from __future__ import annotations

from datetime import date, time, timedelta

from sqlalchemy import select

from .database import SessionLocal
from .models import Project, RoutineItem, ScheduleBlock


PLAN_END_DATE = date(2026, 8, 15)

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

MORNING_ROUTINE = [
    "Подъем",
    "Вода",
    "Душ",
    "Одеться",
    "Прием пищи 1",
    "Собрать еду",
    "Проверить план дня",
]

EVENING_ROUTINE = [
    "Подготовить одежду",
    "Подготовить еду",
    "Быстрая уборка",
    "Гигиена",
    "Поставить будильник",
    "Убрать телефон",
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
            (time(23, 50), time(23, 59), "Сон", "recovery", "Сон"),
        ]
    )
    return blocks


def ensure_seed_data() -> None:
    with SessionLocal() as db:
        projects_by_name: dict[str, Project] = {}

        for name, priority, category in DEFAULT_PROJECTS:
            project = db.scalar(select(Project).where(Project.name == name))
            if project is None:
                project = Project(name=name, priority=priority, category=category)
                db.add(project)
                db.flush()
            else:
                project.priority = priority
                project.category = category
            projects_by_name[name] = project

        has_routines = db.scalar(select(RoutineItem.id).limit(1))
        if has_routines is None:
            for idx, title in enumerate(MORNING_ROUTINE):
                db.add(
                    RoutineItem(
                        routine_name="morning",
                        title=title,
                        position=idx,
                    )
                )
            for idx, title in enumerate(EVENING_ROUTINE):
                db.add(
                    RoutineItem(
                        routine_name="evening",
                        title=title,
                        position=idx,
                    )
                )

        start = date.today()
        end = max(start + timedelta(days=13), PLAN_END_DATE)
        current = start

        while current <= end:
            has_date = db.scalar(
                select(ScheduleBlock.id)
                .where(ScheduleBlock.block_date == current)
                .limit(1)
            )
            if has_date is None:
                for start_time, end_time, title, block_type, project_name in _day_template(current):
                    project = projects_by_name.get(project_name) if project_name else None
                    db.add(
                        ScheduleBlock(
                            block_date=current,
                            start_time=start_time,
                            end_time=end_time,
                            title=title,
                            block_type=block_type,
                            project_id=project.id if project else None,
                        )
                    )
            current += timedelta(days=1)

        db.commit()
