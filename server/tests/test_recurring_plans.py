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
