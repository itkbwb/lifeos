# Life OS — server

FastAPI-сервер. Вся старая бизнес-логика (расписание, блоки, state machine)
снесена — пересобирается заново по главам, глава за главой; текущие главы —
Проекты, Календарь (только Android) и Timeline (история событий).

## Что есть сейчас

- `app/main.py`:
  - `GET /health` — `{"status": "ok", "version": ..., "time": ...}`,
    используется Android-клиентом для проверки подключения к серверу.
  - `GET /api/projects`, `POST /api/projects`, `PATCH /api/projects/{id}`,
    `DELETE /api/projects/{id}` — CRUD над проектами (`id, name, color,
    created_at`; `color` — один из `lavender, blue, green, yellow, orange,
    red, pink, gray`). Удаление запрещено (409), если у проекта уже есть
    события Timeline — история не должна теряться молча.
  - `POST /api/events`, `GET /api/events`, `GET /api/events/active`,
    `POST /api/events/{id}/correct` — Timeline: `start`/`end`/`instant`
    события, каждое принадлежит ровно одному проекту. Одновременно активен
    только один проект — `start` при уже активном другом проекте вернёт 409
    с данными активного проекта. События неизменяемы: исправление ошибки —
    это `correct`, который добавляет новую запись (`corrects_id`) и лишь
    помечает старую как скорректированную (`superseded_by_id`,
    `corrected_at`), не трогая её исходные `occurred_at`/`type`.
- SQLite через SQLAlchemy (`app/database.py`, `app/models.py`,
  `app/schemas.py`), без Alembic — таблицы создаются автоматически при
  старте (`Base.metadata.create_all`). `PRAGMA foreign_keys=ON` включена на
  уровне подключения (иначе SQLite не проверяет внешние ключи), даты
  хранятся через кастомный `UTCDateTime` (SQLite иначе теряет tzinfo при
  round-trip).
- Тесты: `tests/test_projects.py`, `tests/test_events.py` (pytest +
  `TestClient`, in-memory SQLite).

## Запуск

```bash
cd server
chmod +x run_linux.sh
./run_linux.sh
```

Windows:

```text
run_windows.bat
```

По умолчанию слушает `http://0.0.0.0:8000`.

## Постоянный запуск (systemd)

```bash
chmod +x install_service.sh
./install_service.sh
```

Проверка: `sudo systemctl status lifeos`. Логи: `journalctl -u lifeos -f`.

## Данные

`data/lifeos.db` (не коммитится, см. `.gitignore`) — создаётся автоматически
при первом запуске, содержит таблицы `projects` и `events`.
