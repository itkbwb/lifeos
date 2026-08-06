# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Life OS — personal time/project management system, single-user, no auth. Two
components in one repo:

- `server/` — FastAPI + SQLite backend, the source of truth. Runs on a
  Raspberry Pi (or any Linux/Windows host).
- `android/` — native Kotlin/Compose Android client. Talks to the server over
  REST; no local persistence beyond a few settings (server address, Cloudflare
  Access token).

Development proceeds "chapter by chapter" — old business logic was deleted
and is being rebuilt incrementally (Projects → Calendar/Timeline → Recurring
Plans → Reminders/Notifications → Imports, in that order per commit history).
Code comments referencing "chapter: X" are pointers to this staged design,
not TODOs.

## Commands

### Server

```bash
cd server
pip install -r requirements.txt
./run_linux.sh        # or run_windows.bat on Windows; listens on 0.0.0.0:8000
```

Run tests (pytest + `TestClient`, in-memory SQLite):

```bash
cd server
pytest                              # all tests
pytest tests/test_recurring_plans.py -v
pytest tests/test_events.py::test_some_case -v   # single test
```

Persistent deployment (systemd, on the Pi):

```bash
cd server
./install_service.sh
sudo systemctl status lifeos
journalctl -u lifeos -f
```

`data/lifeos.db` is created automatically on first run (not committed).
Tables are created via `Base.metadata.create_all` — there is no Alembic;
schema changes are additive/manual.

### Android

No committed Gradle wrapper — open `android/` in Android Studio (Koala/2024.1+)
and let it regenerate the wrapper, or use a locally installed `gradle`:

```bash
cd android
gradle assembleDebug
gradle testDebugUnitTest                          # all unit tests
gradle testDebugUnitTest --tests "*.TimelineLayoutTest"
```

Release builds are produced by CI only (`.github/workflows/android-release.yml`),
triggered by pushing a `vX.Y.Z` tag — signs with secrets
(`ANDROID_KEYSTORE_BASE64`, etc.), builds via `gradle assembleRelease
-PversionOverride=X.Y.Z`, and publishes the APK as a GitHub Release. The app
checks GitHub Releases on every launch and self-updates (no Play Store).

For manual on-device visual checks of calendar rendering, see
`android/screenshot_scenarios.sh` (requires a running emulator + dev server
seeded via `server/seed_golden_scenarios.py`) — a manual tool, not part of CI.

## Architecture

### Server (`server/app/`)

- `main.py` — all routes live here, no router splitting yet. Organized by
  feature block: health/update-check → Projects → Subtasks → Events (Timeline)
  → Plan entries (Static plan) → Recurring plans (Dynamic plan) → Imports →
  Admin → Notifications/Reminders.
- `models.py` — SQLAlchemy models: `Project`, `Event`, `PlanEntry`,
  `PlanChange`, `RecurringPlan`, `RecurringPlanException`, `Subtask`,
  `DeviceToken`, `Reminder`, `NotificationState`.
- `database.py` — SQLite engine/session setup; `PRAGMA foreign_keys=ON` is
  set explicitly on every connection (SQLite doesn't enforce FKs by default).
  Dates are stored via a custom `UTCDateTime` type because SQLite otherwise
  drops tzinfo on round-trip.
- `recurrence.py` — generates concrete `PlanEntry` occurrences from a
  `RecurringPlan` rule, rolling `GENERATION_HORIZON_DAYS` (30) days ahead of
  "now" — same non-infinite-materialization behavior as Google Calendar.
  `RecurringPlanException`/`PlanChange` handle single-occurrence edits and
  "split from here forward" edits without mutating past occurrences.
- `scheduler.py` — `APScheduler` background job, polls every 60s
  (`CHECK_INTERVAL_SECONDS`) for plan entries about to start and pushes FCM
  notifications. This replaced an earlier Android-side WorkManager approach,
  moved server-side specifically to avoid WorkManager's 15-minute minimum
  interval and Doze/App-Standby throttling.
- `notifications.py` — lazy Firebase Admin SDK init from
  `FCM_SERVICE_ACCOUNT_FILE`; silently no-ops (never raises) if unset, so
  local/dev/CI runs work without a Firebase project existing.

**Timeline events are immutable.** There's no update endpoint for `Event` —
correcting a mistake means calling `POST /api/events/{id}/correct`, which
inserts a new event (`corrects_id`) and marks the old one
(`superseded_by_id`, `corrected_at`) without touching its original
`occurred_at`/`type`. Only one project can have an open `start` (no matching
`end`) at a time — starting another while one is active returns 409 with the
active project's data.

**Two parallel "plan" concepts**, both surfaced to Android as calendar data
but built differently:
- *Static plan* (`PlanEntry`) — one-off or CSV-imported fixed-time entries.
- *Dynamic plan* (`RecurringPlan` + generated `PlanEntry` occurrences) —
  Google-Calendar-style recurrence rules (`recurrence.py`), materialized on a
  rolling window and exposed read-side via `GET /api/plan/dynamic`.

Deleting a `Project` is blocked (409) if it has any Timeline events —
history must never be silently lost; `force=` exists for explicit override,
and `POST /api/projects/merge` exists for consolidating projects instead.

Two importers, deliberately different (`docs/IMPORTS.md` has the full
comparison table): `POST /api/import/csv` (flat CSV → Static plan entries
only, no dedup) and `POST /api/import/project` (JSON task tree → project +
subtasks + optional Static entries, subtask tree deduplicated by
name+parent). Both are best-effort per-row: a bad row is skipped and
reported, not fatal to the whole import.

### Android (`android/app/src/main/java/com/lifeos/app/`)

- `data/` — plain data classes plus `ApiFactory.kt` (OkHttp + Gson client;
  every server call lives here — `GET /health`, Projects CRUD, Timeline
  events, active-project check).
- `ui/calendar/` — the calendar stack: `CalendarScreen.kt` switches between
  Year/Month/Week/Day (`YearView`, `MonthView`, `WeekView`, `DayPager` +
  `DayTimelineView`). `TimelineLayout.kt` computes the geometry (10-minute
  grid, block positions); `DayRenderModel.kt` turns raw events into
  renderable blocks — this split (layout math vs. data model) is what's unit
  tested in `app/src/test/.../calendar/`. `instant` events get a
  deterministic-but-pseudorandom position along their line, seeded from the
  event `id`, so repeated renders don't jitter but neighboring markers don't
  overlap either.
- `ui/*.kt` (top level) — screens/dialogs outside the calendar: Projects,
  Subtasks, Reminders, Settings, CSV/project import review screens, notes
  editor, checklist runner.
- `notifications/` — `LifeOsFirebaseMessagingService` (receives server-
  pushed FCM), `NotificationActionReceiver` + `CreateEventWorker` (in-
  notification action buttons that hit the API directly), `ReminderEvents`.
- `update/UpdateChecker.kt` — compares `versionName` against
  `itkbwb/lifeos` GitHub Releases on every launch; downloads and prompts
  install if newer. All release builds share one signing key (CI secrets)
  specifically so this update flow doesn't require uninstalling first.
- `ui/theme/` — fixed dark lavender theme (`Color.kt`, `Theme.kt`) plus a
  closed set of project colors (`ProjectColors.kt`:
  lavender/blue/green/yellow/orange/red/pink/gray) shared with the server's
  `color` enum on `Project`.

Server address and Cloudflare Access service token are user-configurable
(Settings screen), stored in DataStore / `EncryptedSharedPreferences`
respectively — there's no baked-in auth beyond that token.
`usesCleartextTraffic="true"` is intentional (the server is often reached
over plain HTTP on the LAN).

The FCM/`google-services.json` wiring in `android/app/build.gradle.kts` is
conditional: the `com.google.gms.google-services` plugin only applies if
`google-services.json` is present locally or reconstructed from the
`GOOGLE_SERVICES_JSON_BASE64` CI secret, so builds without a Firebase project
configured still succeed (`BuildConfig.FCM_CONFIGURED` reflects this at
runtime).
