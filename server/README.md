# Life OS — server

FastAPI-сервер. Вся старая бизнес-логика (расписание, блоки, state machine)
снесена — пересобирается заново по главам, глава за главой; текущая глава —
минимальные Проекты.

## Что есть сейчас

- `app/main.py`:
  - `GET /health` — `{"status": "ok", "version": ..., "time": ...}`,
    используется Android-клиентом для проверки подключения к серверу.
  - `GET /api/projects`, `POST /api/projects`, `PATCH /api/projects/{id}`,
    `DELETE /api/projects/{id}` — CRUD над проектами (`id, name, color,
    created_at`; `color` — один из `lavender, blue, green, yellow, orange,
    red, pink, gray`). Удаление окончательное, без корзины/архива.
- SQLite через SQLAlchemy (`app/database.py`, `app/models.py`,
  `app/schemas.py`), без Alembic — таблица создаётся автоматически при
  старте (`Base.metadata.create_all`).
- Тесты: `tests/test_projects.py` (pytest + `TestClient`, in-memory SQLite).

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
при первом запуске, содержит только таблицу `projects`.
