from __future__ import annotations

from datetime import timezone
from pathlib import Path

from sqlalchemy import DateTime, create_engine, event
from sqlalchemy.orm import DeclarativeBase, sessionmaker
from sqlalchemy.types import TypeDecorator

BASE_DIR = Path(__file__).resolve().parent
DATA_DIR = BASE_DIR.parent / "data"
DATA_DIR.mkdir(parents=True, exist_ok=True)

engine = create_engine(
    f"sqlite:///{DATA_DIR / 'lifeos.db'}",
    connect_args={"check_same_thread": False},
)


@event.listens_for(engine, "connect")
def _enable_sqlite_foreign_keys(dbapi_connection, connection_record):
    # SQLite ignores FK constraints (RESTRICT/SET NULL/etc.) unless this is
    # set per-connection - without it, Project.id FK ON DELETE RESTRICT would
    # silently do nothing and let history-destroying deletes through.
    cursor = dbapi_connection.cursor()
    cursor.execute("PRAGMA foreign_keys=ON")
    cursor.close()


SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)


class Base(DeclarativeBase):
    pass


class UTCDateTime(TypeDecorator):
    """SQLite has no real timezone-aware storage - it silently drops tzinfo on
    round-trip through the default DateTime type. This stores everything as
    naive UTC and reattaches UTC tzinfo on read, so timestamps that go in
    timezone-aware come back timezone-aware (required for Timeline events to
    be trustworthy historical facts, not ambiguous naive datetimes)."""

    impl = DateTime
    cache_ok = True

    def process_bind_param(self, value, dialect):
        if value is None:
            return None
        if value.tzinfo is None:
            raise ValueError("naive datetime not allowed; must be timezone-aware")
        return value.astimezone(timezone.utc).replace(tzinfo=None)

    def process_result_value(self, value, dialect):
        if value is None:
            return None
        return value.replace(tzinfo=timezone.utc)


def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()


def ensure_schema_migrations():
    """`Base.metadata.create_all` only creates missing tables, never alters
    existing ones - a new column on an already-existing table (like
    `projects.archived`, added for project archiving) needs a manual,
    idempotent ALTER TABLE here instead of a full migration framework,
    matching this project's no-Alembic convention."""
    with engine.connect() as conn:
        columns = {row[1] for row in conn.exec_driver_sql("PRAGMA table_info(projects)").fetchall()}
        if "archived" not in columns:
            conn.exec_driver_sql("ALTER TABLE projects ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
            conn.commit()
        if "notes" not in columns:
            conn.exec_driver_sql("ALTER TABLE projects ADD COLUMN notes TEXT")
            conn.commit()

        plan_entry_columns = {
            row[1] for row in conn.exec_driver_sql("PRAGMA table_info(plan_entries)").fetchall()
        }
        if "subtask_id" not in plan_entry_columns:
            conn.exec_driver_sql(
                "ALTER TABLE plan_entries ADD COLUMN subtask_id INTEGER REFERENCES subtasks(id) ON DELETE SET NULL"
            )
            conn.commit()
        if "recurring_plan_id" not in plan_entry_columns:
            conn.exec_driver_sql(
                "ALTER TABLE plan_entries ADD COLUMN recurring_plan_id "
                "INTEGER REFERENCES recurring_plans(id) ON DELETE SET NULL"
            )
            conn.commit()

        recurring_plan_columns = {
            row[1] for row in conn.exec_driver_sql("PRAGMA table_info(recurring_plans)").fetchall()
        }
        if "frequency" not in recurring_plan_columns:
            conn.exec_driver_sql("ALTER TABLE recurring_plans ADD COLUMN frequency TEXT NOT NULL DEFAULT 'weekly'")
            conn.commit()
        if "interval" not in recurring_plan_columns:
            conn.exec_driver_sql("ALTER TABLE recurring_plans ADD COLUMN interval INTEGER NOT NULL DEFAULT 1")
            conn.commit()
        if "month_mode" not in recurring_plan_columns:
            conn.exec_driver_sql("ALTER TABLE recurring_plans ADD COLUMN month_mode TEXT")
            conn.commit()
        if "max_occurrences" not in recurring_plan_columns:
            conn.exec_driver_sql("ALTER TABLE recurring_plans ADD COLUMN max_occurrences INTEGER")
            conn.commit()

        subtask_columns = {
            row[1] for row in conn.exec_driver_sql("PRAGMA table_info(subtasks)").fetchall()
        }
        if "notes" not in subtask_columns:
            conn.exec_driver_sql("ALTER TABLE subtasks ADD COLUMN notes TEXT")
            conn.commit()
        if "parent_id" not in subtask_columns:
            conn.exec_driver_sql(
                "ALTER TABLE subtasks ADD COLUMN parent_id INTEGER REFERENCES subtasks(id) ON DELETE CASCADE"
            )
            conn.commit()
        if "is_checklist" not in subtask_columns:
            conn.exec_driver_sql("ALTER TABLE subtasks ADD COLUMN is_checklist INTEGER NOT NULL DEFAULT 0")
            conn.commit()
        if "instant_event_id" not in subtask_columns:
            conn.exec_driver_sql(
                "ALTER TABLE subtasks ADD COLUMN instant_event_id INTEGER REFERENCES events(id) ON DELETE SET NULL"
            )
            conn.commit()
