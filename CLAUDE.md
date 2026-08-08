# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Life OS — персональная система управления временем и проектами. Два компонента в одном репо, разрабатываются по главам (каждая — отдельный PR):

- `server/` — FastAPI + SQLite бэкенд, источник истины.
- `android/` — нативный Kotlin/Compose клиент под Android.

Сервер (Raspberry Pi или любой Linux/Windows хост) хранит всё в SQLite и отдаёт REST API. Android-приложение — тонкий клиент: тянет данные, шлёт действия, адрес сервера настраивается на экране «Настройки». HTTPS/домен идёт через Cloudflare Access, но нет полноценной пользовательской авторизации.

## Commands

### Server (`server/`)

```bash
cd server
./run_linux.sh          # или run_windows.bat на Windows — слушает 0.0.0.0:8000
pytest                  # весь набор тестов
pytest tests/test_events.py            # один файл
pytest tests/test_events.py -k name    # один тест
```

Тесты используют in-memory SQLite через `TestClient` (см. `tests/conftest.py`) — не трогают `data/lifeos.db`. Нет линтера/форматтера, настроенного в репозитории.

Постоянный запуск на Linux (systemd): `./install_service.sh`, затем `sudo systemctl status lifeos` / `journalctl -u lifeos -f`.

### Android (`android/`)

Открывается в Android Studio (`File → Open` → папку `android/`) — `gradlew`-обёртки в репозитории намеренно нет, Android Studio восстанавливает её сама. Если нужна сборка без IDE — системный `gradle`:

```bash
cd android
gradle assembleDebug
gradle testDebugUnitTest                 # весь юнит-тест набор (JVM, без эмулятора)
gradle testDebugUnitTest --tests "*DayRenderModelTest*"   # один класс
```

Юнит-тесты лежат в `android/app/src/test/java/com/lifeos/app/...` (JUnit4, чистый Kotlin — без Robolectric/эмулятора). Инструментальных (`androidTest`) тестов нет.

Debug-сборки бьют в `10.0.2.2:8000` (эмулятор → хост) для проверки обновлений вместо реального GitHub API — см. комментарий в `app/build.gradle.kts`.

## Architecture

### Server: главы = таблицы + эндпоинты, накопительно

`app/main.py` — единственный файл с роутами, без Alembic-миграций: `Base.metadata.create_all` в `app/database.py` создаёт таблицы аддитивно при старте. `type`-подобные поля — обычные строки с Python-валидацией, не нативные SQL enum. Даты хранятся через кастомный `UTCDateTime` (иначе SQLite теряет tzinfo при round-trip). `PRAGMA foreign_keys=ON` включена на уровне подключения.

Текущие главы и их эндпоинты:
- **Проекты** (`/api/projects`, `/api/subtasks`) — CRUD, merge, чеклист-подзадачи, reorder. Удаление проекта запрещено (409), если есть события Timeline.
- **Timeline** (`/api/events`) — `start`/`end`/`instant` события, один активный проект одновременно (`start` при уже активном другом → 409 с данными активного). **События неизменяемы**: исправление — это `correct`, добавляющий новую запись (`corrects_id`) и помечающий старую как superseded (`superseded_by_id`, `corrected_at`), исходные `occurred_at`/`type` не трогаются.
- **Планирование** (`/api/plan/entries`, `/api/recurring-plans`, `/api/plan/changes`, `/api/plan/dynamic`) — Static Plan (неизменяемое намерение) + изменения (moves) + вычисляемый Dynamic Plan. Планирование никогда не создаёт события Timeline автоматически — это независимые слои (см. `app/recurrence.py` для генерации повторяющихся вхождений).
- **Импорт** (`/api/import/csv`, `/api/import/project`) — разбор внешних данных.
- **Уведомления/напоминания** (`/api/notifications/*`, `/api/reminders`) — FCM push через `app/notifications.py`, планирование через APScheduler (`app/scheduler.py`).
- `/api/admin/clear` — полная очистка данных (dev/тестовый эндпоинт).

При добавлении новой главы: новые модели в `app/models.py`, схемы в `app/schemas.py`, роуты в `app/main.py`, тест-файл в `tests/`. Не переписывать существующие таблицы/эндпоинты без явной просьбы — только добавлять.

### Android: без ViewModel/DI/Navigation-Compose — осознанная конвенция

Состояние живёт в `remember`/`mutableStateOf` прямо в композблах, не в отдельных ViewModel-слоях. Это не забытая архитектура, а сознательный выбор для проекта такого размера — не «исправлять» рефакторингом на MVVM без явной просьбы.

- `data/ApiFactory.kt` — OkHttp+Gson клиент ко всем серверным эндпоинтам.
- `data/SettingsStore.kt` — DataStore для адреса сервера; Cloudflare Access Service Token — в `EncryptedSharedPreferences` (`androidx.security:security-crypto`).
- `ui/calendar/` — Year/Month/Week/Day навигация в духе Google Calendar. `TimelineLayout.kt` (`layoutDay`, `IntervalBlockData`/`UnfinishedBlockData`/`InstantMarkerData`) считает геометрию, `DayTimelineView.kt` рисует Timeline-слой внутри `BoxWithConstraints`. Планирование добавляет Dynamic (полупрозрачная заливка, под Timeline) и Static (штриховой контур, над всем) слои в той же системе координат, не переписывая существующую отрисовку.
- `ui/theme/ProjectColors.kt` — цвет проекта как источник истины для заливки; `contrastingTextColor` считает читаемый цвет текста по YIQ-luminance, а не жёстко чёрный/белый.
- `update/UpdateChecker.kt` — сравнивает `versionName` с последним GitHub Release (`itkbwb/lifeos`), предлагает скачать и установить APK. APK не публикуется в Google Play — единственный канал распространения.
- Диалоги сгруппированы по фиче в `ui/*Dialogs.kt` (`ProjectDialogs.kt`, `PlanDialogs.kt`), а не по одному файлу на диалог.

### Релизы Android

Пуш тега `vX.Y.Z` → `.github/workflows/android-release.yml` собирает подписанный release APK и публикует GitHub Release. Все сборки подписаны одним ключом (секреты `ANDROID_KEYSTORE_BASE64` и т.д.) — иначе Android требовал бы удаления старой версии перед установкой новой. Версия приложения (`versionName`/`versionCode`) выводится из тега, не из `build.gradle.kts`.

```bash
git tag v0.1.1
git push origin v0.1.1
```

## Known constraints

- Сервер работает по обычному HTTP в локальной сети (`usesCleartextTraffic="true"` в манифесте) — полноценной авторизации, HTTPS и постоянного внешнего адреса пока нет.
- Первая установка APK на телефон — вручную, дальше работает автообновление.
- Интервалы Timeline, пересекающие полночь, рисуются как два независимых блока без общего состояния между соседними днями.
