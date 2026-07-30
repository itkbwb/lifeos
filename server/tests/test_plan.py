def _make_project(client):
    return client.post("/api/projects", json={"name": "Deep work", "color": "lavender"}).json()


def test_create_plan_entry(client):
    project = _make_project(client)
    resp = client.post(
        "/api/plan/entries",
        json={
            "project_id": project["id"],
            "start_time": "2026-08-01T09:00:00Z",
            "end_time": "2026-08-01T11:00:00Z",
        },
    )
    assert resp.status_code == 201
    body = resp.json()
    assert body["project_id"] == project["id"]
    assert body["name"] is None
    assert set(body.keys()) == {"id", "project_id", "start_time", "end_time", "name", "created_at"}


def test_create_plan_entry_with_name(client):
    project = _make_project(client)
    resp = client.post(
        "/api/plan/entries",
        json={
            "project_id": project["id"],
            "start_time": "2026-08-01T09:00:00Z",
            "end_time": "2026-08-01T11:00:00Z",
            "name": "Deep work block",
        },
    )
    assert resp.status_code == 201
    assert resp.json()["name"] == "Deep work block"

    dynamic = client.get("/api/plan/dynamic").json()
    assert dynamic[0]["name"] == "Deep work block"


def test_create_plan_entry_blank_name_becomes_none(client):
    project = _make_project(client)
    resp = client.post(
        "/api/plan/entries",
        json={
            "project_id": project["id"],
            "start_time": "2026-08-01T09:00:00Z",
            "end_time": "2026-08-01T11:00:00Z",
            "name": "   ",
        },
    )
    assert resp.status_code == 201
    assert resp.json()["name"] is None


def test_create_plan_entry_end_before_start_rejected(client):
    project = _make_project(client)
    resp = client.post(
        "/api/plan/entries",
        json={
            "project_id": project["id"],
            "start_time": "2026-08-01T11:00:00Z",
            "end_time": "2026-08-01T09:00:00Z",
        },
    )
    assert resp.status_code == 422


def test_create_plan_entry_missing_project_404(client):
    resp = client.post(
        "/api/plan/entries",
        json={
            "project_id": 9999,
            "start_time": "2026-08-01T09:00:00Z",
            "end_time": "2026-08-01T11:00:00Z",
        },
    )
    assert resp.status_code == 404


def test_list_plan_entries_filters_by_window(client):
    project = _make_project(client)
    client.post(
        "/api/plan/entries",
        json={
            "project_id": project["id"],
            "start_time": "2026-08-01T09:00:00Z",
            "end_time": "2026-08-01T11:00:00Z",
        },
    )
    client.post(
        "/api/plan/entries",
        json={
            "project_id": project["id"],
            "start_time": "2026-08-05T09:00:00Z",
            "end_time": "2026-08-05T11:00:00Z",
        },
    )
    resp = client.get(
        "/api/plan/entries", params={"from": "2026-08-02T00:00:00Z", "to": "2026-08-06T00:00:00Z"}
    )
    assert resp.status_code == 200
    assert len(resp.json()) == 1


def test_dynamic_plan_matches_static_when_no_changes(client):
    project = _make_project(client)
    client.post(
        "/api/plan/entries",
        json={
            "project_id": project["id"],
            "start_time": "2026-08-01T09:00:00Z",
            "end_time": "2026-08-01T11:00:00Z",
        },
    )
    resp = client.get("/api/plan/dynamic")
    assert resp.status_code == 200
    body = resp.json()
    assert len(body) == 1
    assert body[0]["start_time"] == "2026-08-01T09:00:00Z"
    assert body[0]["end_time"] == "2026-08-01T11:00:00Z"


def test_move_change_updates_dynamic_but_not_static(client):
    project = _make_project(client)
    entry = client.post(
        "/api/plan/entries",
        json={
            "project_id": project["id"],
            "start_time": "2026-08-01T09:00:00Z",
            "end_time": "2026-08-01T11:00:00Z",
        },
    ).json()

    resp = client.post(
        f"/api/plan/entries/{entry['id']}/changes",
        json={
            "change_type": "move",
            "new_start_time": "2026-08-01T13:00:00Z",
            "new_end_time": "2026-08-01T15:00:00Z",
        },
    )
    assert resp.status_code == 201

    static = client.get("/api/plan/entries").json()
    assert static[0]["start_time"] == "2026-08-01T09:00:00Z"

    dynamic = client.get("/api/plan/dynamic").json()
    assert dynamic[0]["start_time"] == "2026-08-01T13:00:00Z"
    assert dynamic[0]["end_time"] == "2026-08-01T15:00:00Z"


def test_move_requires_both_times(client):
    project = _make_project(client)
    entry = client.post(
        "/api/plan/entries",
        json={
            "project_id": project["id"],
            "start_time": "2026-08-01T09:00:00Z",
            "end_time": "2026-08-01T11:00:00Z",
        },
    ).json()
    resp = client.post(
        f"/api/plan/entries/{entry['id']}/changes",
        json={"change_type": "move", "new_start_time": "2026-08-01T13:00:00Z"},
    )
    assert resp.status_code == 422


def test_cancel_change_excludes_from_dynamic(client):
    project = _make_project(client)
    entry = client.post(
        "/api/plan/entries",
        json={
            "project_id": project["id"],
            "start_time": "2026-08-01T09:00:00Z",
            "end_time": "2026-08-01T11:00:00Z",
        },
    ).json()

    resp = client.post(f"/api/plan/entries/{entry['id']}/changes", json={"change_type": "cancel"})
    assert resp.status_code == 201

    dynamic = client.get("/api/plan/dynamic").json()
    assert dynamic == []

    static = client.get("/api/plan/entries").json()
    assert len(static) == 1


def test_latest_change_wins(client):
    project = _make_project(client)
    entry = client.post(
        "/api/plan/entries",
        json={
            "project_id": project["id"],
            "start_time": "2026-08-01T09:00:00Z",
            "end_time": "2026-08-01T11:00:00Z",
        },
    ).json()

    client.post(
        f"/api/plan/entries/{entry['id']}/changes",
        json={
            "change_type": "move",
            "new_start_time": "2026-08-01T13:00:00Z",
            "new_end_time": "2026-08-01T15:00:00Z",
        },
    )
    client.post(f"/api/plan/entries/{entry['id']}/changes", json={"change_type": "cancel"})

    dynamic = client.get("/api/plan/dynamic").json()
    assert dynamic == []


def test_invalid_change_type_rejected(client):
    project = _make_project(client)
    entry = client.post(
        "/api/plan/entries",
        json={
            "project_id": project["id"],
            "start_time": "2026-08-01T09:00:00Z",
            "end_time": "2026-08-01T11:00:00Z",
        },
    ).json()
    resp = client.post(f"/api/plan/entries/{entry['id']}/changes", json={"change_type": "delete"})
    assert resp.status_code == 422


def test_change_missing_entry_404(client):
    resp = client.post("/api/plan/entries/9999/changes", json={"change_type": "cancel"})
    assert resp.status_code == 404


# --- Chapter 4 scenario 16: Static/Dynamic Plan and Timeline are independent domains ---


def test_creating_an_event_does_not_create_a_plan_entry(client):
    project = _make_project(client)
    client.post(
        "/api/events",
        json={"project_id": project["id"], "type": "start", "occurred_at": "2026-08-01T09:00:00Z"},
    )
    assert client.get("/api/plan/entries").json() == []
    assert client.get("/api/plan/dynamic").json() == []


def test_creating_a_plan_entry_does_not_create_an_event(client):
    project = _make_project(client)
    client.post(
        "/api/plan/entries",
        json={
            "project_id": project["id"],
            "start_time": "2026-08-01T09:00:00Z",
            "end_time": "2026-08-01T10:00:00Z",
        },
    )
    assert client.get("/api/events").json() == []


def test_a_plan_change_does_not_create_or_touch_any_event(client):
    project = _make_project(client)
    entry = client.post(
        "/api/plan/entries",
        json={
            "project_id": project["id"],
            "start_time": "2026-08-01T09:00:00Z",
            "end_time": "2026-08-01T10:00:00Z",
        },
    ).json()
    client.post(
        f"/api/plan/entries/{entry['id']}/changes",
        json={
            "change_type": "move",
            "new_start_time": "2026-08-01T11:00:00Z",
            "new_end_time": "2026-08-01T12:00:00Z",
        },
    )
    assert client.get("/api/events").json() == []


def test_an_active_plan_entry_does_not_block_or_alter_starting_an_event(client):
    """Planning and Timeline are independent domains (chapter 4.4) - a Static Plan
    entry covering right now must not affect the existing START/END conflict rule
    (only another *active Timeline* session blocks a start, per chapter 3)."""
    project = _make_project(client)
    client.post(
        "/api/plan/entries",
        json={
            "project_id": project["id"],
            "start_time": "2026-08-01T09:00:00Z",
            "end_time": "2026-08-01T10:00:00Z",
        },
    )
    resp = client.post(
        "/api/events",
        json={"project_id": project["id"], "type": "start", "occurred_at": "2026-08-01T09:30:00Z"},
    )
    assert resp.status_code == 201

    # The plan entry itself is untouched by the event that just happened inside its span.
    plan_entries = client.get("/api/plan/entries").json()
    assert len(plan_entries) == 1
    assert plan_entries[0]["start_time"] == "2026-08-01T09:00:00Z"
    assert plan_entries[0]["end_time"] == "2026-08-01T10:00:00Z"


def test_correcting_an_event_does_not_touch_plan_entries(client):
    project = _make_project(client)
    client.post(
        "/api/plan/entries",
        json={
            "project_id": project["id"],
            "start_time": "2026-08-01T09:00:00Z",
            "end_time": "2026-08-01T10:00:00Z",
        },
    )
    event = client.post(
        "/api/events",
        json={"project_id": project["id"], "type": "start", "occurred_at": "2026-08-01T09:30:00Z"},
    ).json()
    client.post(f"/api/events/{event['id']}/correct", json={"label": "corrected"})

    plan_entries = client.get("/api/plan/entries").json()
    assert len(plan_entries) == 1
    assert plan_entries[0]["start_time"] == "2026-08-01T09:00:00Z"
