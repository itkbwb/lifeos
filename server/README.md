# Life OS — server

Минимальный FastAPI-каркас. Вся предыдущая бизнес-логика (проекты,
расписание, блоки, state machine, БД) снесена — будет спроектирована и
написана заново.

## Что есть сейчас

- `app/main.py` — приложение FastAPI с одним эндпоинтом:
  - `GET /health` — `{"status": "ok", "version": ..., "time": ...}`,
    используется Android-клиентом для проверки подключения к серверу.
- Никакой базы данных, ORM или бизнес-логики в коде нет.

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

Реальных пользовательских данных сервер сейчас не хранит — БД удалена
вместе со старой бизнес-логикой при демонтаже. Когда появится новая схема,
здесь снова будет `data/lifeos.db` (не коммитится, см. `.gitignore`).
