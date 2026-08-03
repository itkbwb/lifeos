from datetime import date, timedelta


def _make_project(client, name="Routine"):
    return client.post("/api/projects", json={"name": name, "color": "lavender"}).json()


def _create_daily(client, project_id, weekdays="1,2,3,4,5,6,7", series_start_date=None, series_end_date=None):
    payload = {
        "project_id": project_id,
        "start_time_of_day": "09:00",
        "end_time_of_day": "09:30",
        "weekdays": weekdays,
        "timezone": "UTC",
        "series_start_date": (series_start_date or date.today()).isoformat(),
    }
    if series_end_date is not None:
        payload["series_end_date"] = series_end_date.isoformat()
    resp = client.post("/api/recurring-plans", json=payload)
    assert resp.status_code == 201, resp.text
    return resp.json()


def _recurring_entries(client, project_id, plan_id):
    entries = client.get("/api/plan/entries", params={"project_id": project_id}).json()
    return sorted((e for e in entries if e["recurring_plan_id"] == plan_id), key=lambda e: e["start_time"])


def test_create_recurring_plan_generates_full_horizon(client):
    project = _make_project(client)
    plan = _create_daily(client, project["id"])
    entries = _recurring_entries(client, project["id"], plan["id"])
    # today..today+29 inclusive = 30 days (GENERATION_HORIZON_DAYS)
    assert len(entries) == 30


def test_recurring_plan_rejects_bad_time_of_day(client):
    project = _make_project(client)
    resp = client.post(
        "/api/recurring-plans",
        json={
            "project_id": project["id"],
            "start_time_of_day": "9am",
            "end_time_of_day": "09:30",
            "weekdays": "1,2,3,4,5",
            "timezone": "UTC",
            "series_start_date": date.today().isoformat(),
        },
    )
    assert resp.status_code == 422


def test_recurring_plan_rejects_end_before_start(client):
    project = _make_project(client)
    resp = client.post(
        "/api/recurring-plans",
        json={
            "project_id": project["id"],
            "start_time_of_day": "10:00",
            "end_time_of_day": "09:00",
            "weekdays": "1,2,3,4,5",
            "timezone": "UTC",
            "series_start_date": date.today().isoformat(),
        },
    )
    assert resp.status_code == 422


def test_recurring_plan_rejects_unknown_timezone(client):
    project = _make_project(client)
    resp = client.post(
        "/api/recurring-plans",
        json={
            "project_id": project["id"],
            "start_time_of_day": "09:00",
            "end_time_of_day": "09:30",
            "weekdays": "1,2,3,4,5",
            "timezone": "Not/AZone",
            "series_start_date": date.today().isoformat(),
        },
    )
    assert resp.status_code == 422


def test_recurring_plan_weekday_filter(client):
    project = _make_project(client)
    plan = _create_daily(client, project["id"], weekdays="6,7")  # weekends only
    entries = _recurring_entries(client, project["id"], plan["id"])
    assert entries
    for e in entries:
        occurrence_date = date.fromisoformat(e["start_time"][:10])
        assert occurrence_date.isoweekday() in {6, 7}


def test_recurring_plan_series_end_date_caps_generation(client):
    project = _make_project(client)
    today = date.today()
    plan = _create_daily(client, project["id"], series_end_date=today + timedelta(days=5))
    entries = _recurring_entries(client, project["id"], plan["id"])
    assert len(entries) == 6  # today..today+5 inclusive


def test_delete_this_occurrence_only_is_not_regenerated(client):
    project = _make_project(client)
    plan = _create_daily(client, project["id"])
    entries = _recurring_entries(client, project["id"], plan["id"])
    first = entries[0]

    resp = client.delete(f"/api/plan/entries/{first['id']}")
    assert resp.status_code == 204

    # Force a resync (the "all" edit path always deletes+regenerates future occurrences) -
    # the deleted date must not come back, proving the exception held.
    client.patch(f"/api/recurring-plans/{plan['id']}", json={})
    entries_after = _recurring_entries(client, project["id"], plan["id"])
    # SQLite may reuse the deleted row's id for a newer insert, so compare by date, not id.
    assert first["start_time"] not in {e["start_time"] for e in entries_after}
    assert len(entries_after) == 29


def test_stop_recurrence_this_and_following(client):
    project = _make_project(client)
    plan = _create_daily(client, project["id"])
    entries = _recurring_entries(client, project["id"], plan["id"])
    cutoff = entries[10]  # 11th day of the series

    resp = client.post(f"/api/plan/entries/{cutoff['id']}/recurrence/stop")
    assert resp.status_code == 204

    entries_after = _recurring_entries(client, project["id"], plan["id"])
    assert len(entries_after) == 10  # only the first 10 days remain
    assert all(e["start_time"] < cutoff["start_time"] for e in entries_after)


def test_split_recurrence_this_and_following(client):
    project = _make_project(client)
    plan = _create_daily(client, project["id"])
    entries = _recurring_entries(client, project["id"], plan["id"])
    split_point = entries[10]

    resp = client.post(
        f"/api/plan/entries/{split_point['id']}/recurrence/split",
        json={
            "project_id": project["id"],
            "name": "Renamed routine",
            "start_time_of_day": "07:00",
            "end_time_of_day": "07:15",
            "weekdays": "1,2,3,4,5,6,7",
        },
    )
    assert resp.status_code == 201, resp.text
    new_plan = resp.json()
    assert new_plan["id"] != plan["id"]
    assert new_plan["start_time_of_day"] == "07:00"

    old_entries_after = _recurring_entries(client, project["id"], plan["id"])
    new_entries = _recurring_entries(client, project["id"], new_plan["id"])
    assert len(old_entries_after) == 10  # original series ends the day before the split
    assert len(new_entries) == 20  # new series picks up the remaining 20 days
    for e in new_entries:
        assert e["name"] == "Renamed routine"
        assert e["start_time"][11:16] == "07:00"


def test_split_recurrence_keeps_original_weekdays_when_omitted(client):
    project = _make_project(client)
    plan = _create_daily(client, project["id"], weekdays="6,7")  # weekends only
    entries = _recurring_entries(client, project["id"], plan["id"])
    split_point = entries[0]

    resp = client.post(
        f"/api/plan/entries/{split_point['id']}/recurrence/split",
        json={
            "project_id": project["id"],
            "start_time_of_day": "08:00",
            "end_time_of_day": "08:30",
        },
    )
    assert resp.status_code == 201, resp.text
    assert resp.json()["weekdays"] == "6,7"


def test_update_all_edits_future_occurrences_only(client):
    project = _make_project(client)
    plan = _create_daily(client, project["id"])

    resp = client.patch(f"/api/recurring-plans/{plan['id']}", json={"name": "Updated name"})
    assert resp.status_code == 200
    assert resp.json()["name"] == "Updated name"

    entries_after = _recurring_entries(client, project["id"], plan["id"])
    assert len(entries_after) == 30
    assert all(e["name"] == "Updated name" for e in entries_after)


def test_delete_all_removes_future_but_keeps_past_history(client):
    project = _make_project(client)
    plan = _create_daily(client, project["id"])
    entries = _recurring_entries(client, project["id"], plan["id"])
    # Simulate one occurrence having already happened by backdating it directly.
    past_entry = entries[0]
    client.patch(
        f"/api/plan/entries/{past_entry['id']}",
        json={"start_time": "2020-01-01T09:00:00Z", "end_time": "2020-01-01T09:30:00Z"},
    )

    resp = client.delete(f"/api/recurring-plans/{plan['id']}")
    assert resp.status_code == 204

    all_entries = client.get("/api/plan/entries", params={"project_id": project["id"]}).json()
    assert len(all_entries) == 1
    assert all_entries[0]["id"] == past_entry["id"]
    assert all_entries[0]["recurring_plan_id"] is None  # detached, not destroyed

    assert client.get("/api/recurring-plans").json() == []


def _create_plan(client, project_id, **overrides):
    payload = {
        "project_id": project_id,
        "start_time_of_day": "09:00",
        "end_time_of_day": "09:30",
        "timezone": "UTC",
        "series_start_date": date.today().isoformat(),
    }
    payload.update(overrides)
    resp = client.post("/api/recurring-plans", json=payload)
    assert resp.status_code == 201, resp.text
    return resp.json()


def test_daily_frequency_respects_interval(client):
    project = _make_project(client)
    today = date.today()
    plan = _create_plan(client, project["id"], frequency="daily", interval=3)
    entries = _recurring_entries(client, project["id"], plan["id"])
    assert entries
    for e in entries:
        d = date.fromisoformat(e["start_time"][:10])
        assert (d - today).days % 3 == 0


def test_weekly_frequency_respects_interval(client):
    project = _make_project(client)
    today = date.today()
    plan = _create_plan(
        client, project["id"], frequency="weekly", interval=2, weekdays=str(today.isoweekday()),
    )
    entries = _recurring_entries(client, project["id"], plan["id"])
    assert entries
    week_start_of_series = today - timedelta(days=today.isoweekday() - 1)
    for e in entries:
        d = date.fromisoformat(e["start_time"][:10])
        assert d.isoweekday() == today.isoweekday()
        week_start_of_d = d - timedelta(days=d.isoweekday() - 1)
        assert (week_start_of_d - week_start_of_series).days // 7 % 2 == 0


def test_monthly_day_of_month(client):
    project = _make_project(client)
    today = date.today()
    plan = _create_plan(client, project["id"], frequency="monthly", month_mode="day_of_month")
    entries = _recurring_entries(client, project["id"], plan["id"])
    assert entries  # today itself always matches its own day-of-month
    for e in entries:
        d = date.fromisoformat(e["start_time"][:10])
        assert d.day == today.day


def test_monthly_weekday_of_month(client):
    project = _make_project(client)
    today = date.today()
    plan = _create_plan(client, project["id"], frequency="monthly", month_mode="weekday_of_month")
    entries = _recurring_entries(client, project["id"], plan["id"])
    assert entries
    for e in entries:
        d = date.fromisoformat(e["start_time"][:10])
        assert d.isoweekday() == today.isoweekday()
        # Same ordinal-in-month occurrence of that weekday as `today`.
        assert (d.day - 1) // 7 == (today.day - 1) // 7


def test_yearly_frequency(client):
    project = _make_project(client)
    today = date.today()
    plan = _create_plan(client, project["id"], frequency="yearly")
    entries = _recurring_entries(client, project["id"], plan["id"])
    assert entries
    for e in entries:
        d = date.fromisoformat(e["start_time"][:10])
        assert (d.month, d.day) == (today.month, today.day)


def test_max_occurrences_caps_total_regardless_of_horizon(client):
    project = _make_project(client)
    plan = _create_plan(client, project["id"], frequency="daily", weekdays=None, max_occurrences=5)
    entries = _recurring_entries(client, project["id"], plan["id"])
    assert len(entries) == 5


def test_weekly_without_weekdays_rejected(client):
    project = _make_project(client)
    resp = client.post(
        "/api/recurring-plans",
        json={
            "project_id": project["id"],
            "start_time_of_day": "09:00",
            "end_time_of_day": "09:30",
            "frequency": "weekly",
            "timezone": "UTC",
            "series_start_date": date.today().isoformat(),
        },
    )
    assert resp.status_code == 422


def test_monthly_defaults_month_mode_to_day_of_month(client):
    project = _make_project(client)
    plan = _create_plan(client, project["id"], frequency="monthly")
    assert plan["month_mode"] == "day_of_month"


def test_update_all_can_change_frequency_and_regenerate(client):
    project = _make_project(client)
    plan = _create_daily(client, project["id"])  # weekly, every day of week

    resp = client.patch(
        f"/api/recurring-plans/{plan['id']}",
        json={"frequency": "daily", "interval": 1, "weekdays": None},
    )
    assert resp.status_code == 200
    assert resp.json()["frequency"] == "daily"

    entries_after = _recurring_entries(client, project["id"], plan["id"])
    assert len(entries_after) == 30


def test_update_all_can_clear_max_occurrences(client):
    project = _make_project(client)
    plan = _create_plan(client, project["id"], frequency="daily", weekdays=None, max_occurrences=3)
    assert len(_recurring_entries(client, project["id"], plan["id"])) == 3

    resp = client.patch(f"/api/recurring-plans/{plan['id']}", json={"max_occurrences": None})
    assert resp.status_code == 200
    assert resp.json()["max_occurrences"] is None

    entries_after = _recurring_entries(client, project["id"], plan["id"])
    assert len(entries_after) == 30


def test_split_recurrence_inherits_frequency_and_max_occurrences(client):
    project = _make_project(client)
    plan = _create_plan(client, project["id"], frequency="daily", weekdays=None, max_occurrences=15)
    entries = _recurring_entries(client, project["id"], plan["id"])
    split_point = entries[5]

    resp = client.post(
        f"/api/plan/entries/{split_point['id']}/recurrence/split",
        json={
            "project_id": project["id"],
            "start_time_of_day": "08:00",
            "end_time_of_day": "08:15",
        },
    )
    assert resp.status_code == 201, resp.text
    new_plan = resp.json()
    assert new_plan["frequency"] == "daily"
    # The new series gets its own fresh 15-occurrence budget starting from the split point,
    # not "whatever quota remained" - same as weekdays/frequency being inherited as an
    # independent copy of the pattern rather than a continuation.
    assert new_plan["max_occurrences"] == 15
    new_entries = _recurring_entries(client, project["id"], new_plan["id"])
    assert len(new_entries) == 15
