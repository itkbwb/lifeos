"""Seeds the running dev server with the manually-approved chapter 4.6 test
scenarios (three-layer stacking: Static/Dynamic Plan + Timeline), each on its
own calendar date so they don't collide and can be viewed independently.

Not an automated test - a fixture database for the "screenshot on demand,
human eyeballs judge it" workflow: point the emulator's local server at this
data (see the android-emulator-testing-workflow memory), open the Day view
on the date printed for a scenario, and screenshot it.

Usage: python seed_golden_scenarios.py [server_url]
(default server_url: http://127.0.0.1:8000)
"""
from __future__ import annotations

import sys
from datetime import date, datetime, time, timedelta

import httpx

SERVER_URL = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8000"

# One project per real production palette color (see ProjectColors.palette).
PALETTE = [
    ("Lavender", "lavender"),
    ("Blue", "blue"),
    ("Green", "green"),
    ("Yellow", "yellow"),
    ("Orange", "orange"),
    ("Red", "red"),
    ("Pink", "pink"),
    ("Gray", "gray"),
]

# Each scenario gets its own date so switching the Day view's date is enough
# to isolate it - no risk of one scenario's events bleeding into another.
#
# instants_with_layers must be the LATEST date and must be seeded last: the
# server's "one active session at a time" check (get_active_start) replays
# *all* start/end events in true chronological order by occurred_at, not by
# insertion order - so a scenario's own open session gets pulled from an
# earlier date by a same-project start/end pair that already exists on a
# later date. instants_with_layers deliberately leaves a session open
# forever (the "unfinished activity" fixture), so it must come chronologically
# after everything else or it - or anything seeded after it - breaks.
SCENARIO_DATES = {
    "all_project_colors_part_1": date(2026, 6, 1),
    "all_project_colors_part_2": date(2026, 6, 2),
    "layer_geometry": date(2026, 6, 3),
    "touching_intervals": date(2026, 6, 4),
    "short_intervals": date(2026, 6, 5),
    "instants_with_layers": date(2026, 6, 6),
}


def iso(d: date, t: time) -> str:
    return datetime.combine(d, t).isoformat() + "Z"


class Client:
    def __init__(self, base_url: str):
        self.http = httpx.Client(base_url=base_url, timeout=10.0)
        self.project_ids: dict[str, int] = {}

    def ensure_projects(self) -> None:
        existing = {p["color"]: p["id"] for p in self.http.get("/api/projects").json()}
        for name, color in PALETTE:
            if color in existing:
                self.project_ids[color] = existing[color]
                continue
            resp = self.http.post("/api/projects", json={"name": name, "color": color})
            resp.raise_for_status()
            self.project_ids[color] = resp.json()["id"]

    def start(self, color: str, d: date, t: time, label: str | None = None) -> None:
        self.http.post(
            "/api/events",
            json={
                "project_id": self.project_ids[color],
                "type": "start",
                "occurred_at": iso(d, t),
                "label": label,
            },
        ).raise_for_status()

    def end(self, color: str, d: date, t: time) -> None:
        self.http.post(
            "/api/events",
            json={
                "project_id": self.project_ids[color],
                "type": "end",
                "occurred_at": iso(d, t),
            },
        ).raise_for_status()

    def instant(self, color: str, d: date, t: time, label: str | None = None) -> None:
        self.http.post(
            "/api/events",
            json={
                "project_id": self.project_ids[color],
                "type": "instant",
                "occurred_at": iso(d, t),
                "label": label,
            },
        ).raise_for_status()

    def static_plan(
        self, color: str, d: date, start: time, end_t: time, name: str | None = None
    ) -> int:
        resp = self.http.post(
            "/api/plan/entries",
            json={
                "project_id": self.project_ids[color],
                "start_time": iso(d, start),
                "end_time": iso(d, end_t),
                "name": name,
            },
        )
        resp.raise_for_status()
        return resp.json()["id"]

    def move_plan(self, entry_id: int, d: date, new_start: time, new_end: time) -> None:
        self.http.post(
            f"/api/plan/entries/{entry_id}/changes",
            json={
                "change_type": "move",
                "new_start_time": iso(d, new_start),
                "new_end_time": iso(d, new_end),
            },
        ).raise_for_status()


def color_showcase_block(c: Client, d: date, color: str, start: time, label: str) -> None:
    """One [start, start+90min] block: Static and Dynamic span the full 90
    minutes, Timeline covers only the second half (45min-90min) - shows bare
    Dynamic, a Dynamic label, opaque Timeline, a Timeline label, and the
    Static dashed outline over both, all at once."""
    end_dt = (datetime.combine(d, start) + timedelta(minutes=90)).time()
    mid_dt = (datetime.combine(d, start) + timedelta(minutes=45)).time()
    c.static_plan(color, d, start, end_dt)
    c.start(color, d, mid_dt, label=f"{label} work")
    c.end(color, d, end_dt)


def seed_all_project_colors(c: Client, d: date, colors: list[str]) -> None:
    starts = [time(8, 0), time(10, 0), time(12, 0), time(14, 0)]
    for color, start in zip(colors, starts):
        color_showcase_block(c, d, color, start, color.capitalize())


def seed_layer_geometry(c: Client, d: date) -> None:
    # A (08:00-10:00, Blue): Timeline starts after the plan begins and
    # continues after the plan ends - plan-only, triple-overlap, and
    # timeline-only zones all visible in sequence.
    c.static_plan("blue", d, time(8, 0), time(9, 30))
    c.start("blue", d, time(8, 30), label="A work")
    c.end("blue", d, time(10, 0))

    # B (10:30-13:30, Green): all three layers offset from each other -
    # static-only, static+dynamic, all-three, static+timeline, timeline-only.
    entry_b = c.static_plan("green", d, time(10, 30), time(12, 30))
    c.move_plan(entry_b, d, time(11, 0), time(12, 0))
    c.start("green", d, time(11, 30), label="B work")
    c.end("green", d, time(13, 30))

    # C (14:00-15:30, Yellow): the Dynamic Plan moved to a later time than
    # the original Static intent (project reassignment isn't something the
    # real API supports - PlanChange only moves time, see schemas.py - so
    # unlike the old Paparazzi fixture this diverges only in time, not
    # project).
    entry_c = c.static_plan("yellow", d, time(14, 0), time(15, 30))
    c.move_plan(entry_c, d, time(16, 0), time(17, 30))
    c.start("yellow", d, time(16, 0), label="C actual")
    c.end("yellow", d, time(17, 30))


def seed_touching_intervals(c: Client, d: date) -> None:
    # Static: four colors touching back-to-back, no gaps, no overlap.
    for color, start, end_t in [
        ("lavender", time(8, 0), time(8, 40)),
        ("blue", time(8, 40), time(9, 20)),
        ("green", time(9, 20), time(10, 0)),
        ("yellow", time(10, 0), time(10, 40)),
    ]:
        c.static_plan(color, d, start, end_t)

    # Dynamic (via move, same project): another four colors touching back-to-back.
    for color, start, end_t in [
        ("orange", time(11, 0), time(11, 40)),
        ("red", time(11, 40), time(12, 20)),
        ("pink", time(12, 20), time(13, 0)),
        ("gray", time(13, 0), time(13, 40)),
    ]:
        entry_id = c.static_plan(color, d, start, end_t, name=color.capitalize())
        c.move_plan(entry_id, d, start, end_t)

    # Timeline: touching intervals alternating projects.
    for color, start, end_t, label in [
        ("lavender", time(14, 0), time(14, 40), "Lavender"),
        ("green", time(14, 40), time(15, 20), "Green"),
        ("orange", time(15, 20), time(16, 0), "Orange"),
        ("pink", time(16, 0), time(16, 40), "Pink"),
    ]:
        c.start(color, d, start, label=label)
        c.end(color, d, end_t)


def seed_instants_with_layers(c: Client, d: date) -> None:
    # Timeline (Lavender), 08:00-09:00, with 6 INSTANTs in and around it -
    # spaced 6-15 minutes apart (never exactly 1 minute), so their
    # deterministic horizontal offsets stay visually distinguishable.
    c.start("lavender", d, time(8, 0), label="Timeline A")
    c.instant("lavender", d, time(8, 7), label="i1")
    c.instant("lavender", d, time(8, 13), label="i2")
    c.instant("lavender", d, time(8, 22), label="i3")
    c.instant("lavender", d, time(8, 31), label="i4")
    c.instant("lavender", d, time(8, 46), label="i5")
    c.end("lavender", d, time(9, 0))
    c.instant("lavender", d, time(9, 2), label="i6")  # just after Timeline ends

    # Planning only (Blue), no Timeline - 2 INSTANTs inside its span.
    entry_id = c.static_plan("blue", d, time(10, 0), time(11, 0), name="Planning B")
    c.move_plan(entry_id, d, time(10, 0), time(11, 0))
    c.instant("blue", d, time(10, 11), label="i7")
    c.instant("blue", d, time(10, 37), label="i8")

    # Open (unfinished) activity, Green, started at 12:00, never closed -
    # one INSTANT inside its 20-minute fade window, one just after it.
    c.start("green", d, time(12, 0), label="Unfinished C")
    c.instant("green", d, time(12, 9), label="i9")
    c.instant("green", d, time(12, 41), label="i10")


def seed_short_intervals(c: Client, d: date) -> None:
    # 10-minute Timeline interval.
    c.start("lavender", d, time(8, 0), label="10 min")
    c.end("lavender", d, time(8, 10))

    # 20-minute Dynamic-only block (via move).
    entry_20 = c.static_plan("blue", d, time(8, 30), time(8, 50), name="20 min")
    c.move_plan(entry_20, d, time(8, 30), time(8, 50))

    # 30-minute Static-only block.
    c.static_plan("green", d, time(9, 10), time(9, 40))

    # 60-minute Static+Dynamic+Timeline all together (Yellow), Timeline
    # covering the second half.
    entry_60 = c.static_plan("yellow", d, time(10, 0), time(11, 0), name="D plan")
    c.move_plan(entry_60, d, time(10, 0), time(11, 0))
    c.start("yellow", d, time(10, 30), label="D work")
    c.end("yellow", d, time(11, 0))


def main() -> None:
    c = Client(SERVER_URL)
    c.ensure_projects()

    seed_all_project_colors(c, SCENARIO_DATES["all_project_colors_part_1"], ["lavender", "blue", "green", "yellow"])
    seed_all_project_colors(c, SCENARIO_DATES["all_project_colors_part_2"], ["orange", "red", "pink", "gray"])
    seed_layer_geometry(c, SCENARIO_DATES["layer_geometry"])
    seed_touching_intervals(c, SCENARIO_DATES["touching_intervals"])
    seed_short_intervals(c, SCENARIO_DATES["short_intervals"])
    # Leaves a Green session open forever (the "unfinished activity" fixture) -
    # the API's "only one active session at a time" rule is global, not
    # per-date, so this must be seeded last or it blocks every start after it.
    seed_instants_with_layers(c, SCENARIO_DATES["instants_with_layers"])

    print("Seeded scenarios (open the Day view on each date to screenshot it):")
    for name, d in SCENARIO_DATES.items():
        print(f"  {d.isoformat()}  {name}")


if __name__ == "__main__":
    main()
