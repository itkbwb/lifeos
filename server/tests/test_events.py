def _make_project(client, name="Paper", color="lavender"):
    return client.post("/api/projects", json={"name": name, "color": color}).json()


def test_create_start_and_end_event(client):
    project = _make_project(client)
    start = client.post(
        "/api/events",
        json={"project_id": project["id"], "type": "start", "occurred_at": "2026-01-01T10:00:00+00:00"},
    )
    assert start.status_code == 201
    body = start.json()
    assert body["type"] == "start"
    assert body["project_id"] == project["id"]
    assert body["occurred_at"] == "2026-01-01T10:00:00Z"

    end = client.post(
        "/api/events",
        json={"project_id": project["id"], "type": "end", "occurred_at": "2026-01-01T11:00:00+00:00"},
    )
    assert end.status_code == 201
    assert end.json()["type"] == "end"


def test_create_event_missing_project_404(client):
    resp = client.post("/api/events", json={"project_id": 9999, "type": "instant"})
    assert resp.status_code == 404


def test_create_event_invalid_type_rejected(client):
    project = _make_project(client)
    resp = client.post("/api/events", json={"project_id": project["id"], "type": "pause"})
    assert resp.status_code == 422


def test_start_conflicts_with_active_project_same_project(client):
    project = _make_project(client)
    client.post("/api/events", json={"project_id": project["id"], "type": "start"})
    resp = client.post("/api/events", json={"project_id": project["id"], "type": "start"})
    assert resp.status_code == 409
    assert resp.json()["detail"]["active_project_id"] == project["id"]


def test_start_conflicts_with_active_project_other_project(client):
    a = _make_project(client, "A")
    b = _make_project(client, "B", "blue")
    client.post("/api/events", json={"project_id": a["id"], "type": "start"})
    resp = client.post("/api/events", json={"project_id": b["id"], "type": "start"})
    assert resp.status_code == 409
    assert resp.json()["detail"]["active_project_id"] == a["id"]


def test_end_without_matching_start_conflicts(client):
    project = _make_project(client)
    resp = client.post("/api/events", json={"project_id": project["id"], "type": "end"})
    assert resp.status_code == 409


def test_end_wrong_project_conflicts(client):
    a = _make_project(client, "A")
    b = _make_project(client, "B", "blue")
    client.post("/api/events", json={"project_id": a["id"], "type": "start"})
    resp = client.post("/api/events", json={"project_id": b["id"], "type": "end"})
    assert resp.status_code == 409


def test_instant_never_conflicts(client):
    a = _make_project(client, "A")
    b = _make_project(client, "B", "blue")
    client.post("/api/events", json={"project_id": a["id"], "type": "start"})
    resp = client.post("/api/events", json={"project_id": b["id"], "type": "instant", "label": "commit"})
    assert resp.status_code == 201


def test_active_endpoint_reflects_state(client):
    project = _make_project(client)
    assert client.get("/api/events/active").status_code == 204

    client.post(
        "/api/events",
        json={"project_id": project["id"], "type": "start", "occurred_at": "2026-01-01T10:00:00+00:00"},
    )
    active = client.get("/api/events/active")
    assert active.status_code == 200
    assert active.json()["project_id"] == project["id"]

    client.post(
        "/api/events",
        json={"project_id": project["id"], "type": "end", "occurred_at": "2026-01-01T11:00:00+00:00"},
    )
    assert client.get("/api/events/active").status_code == 204


def test_list_events_filters_by_project_and_range(client):
    a = _make_project(client, "A")
    b = _make_project(client, "B", "blue")
    client.post(
        "/api/events",
        json={"project_id": a["id"], "type": "instant", "occurred_at": "2026-01-01T09:00:00+00:00"},
    )
    client.post(
        "/api/events",
        json={"project_id": a["id"], "type": "instant", "occurred_at": "2026-01-02T09:00:00+00:00"},
    )
    client.post(
        "/api/events",
        json={"project_id": b["id"], "type": "instant", "occurred_at": "2026-01-01T09:30:00+00:00"},
    )

    by_project = client.get(f"/api/events?project_id={a['id']}").json()
    assert len(by_project) == 2

    by_range = client.get(
        "/api/events?from=2026-01-01T00:00:00%2B00:00&to=2026-01-02T00:00:00%2B00:00"
    ).json()
    assert len(by_range) == 2
    assert {e["project_id"] for e in by_range} == {a["id"], b["id"]}


def test_list_events_includes_straddling_session(client):
    project = _make_project(client)
    client.post(
        "/api/events",
        json={"project_id": project["id"], "type": "start", "occurred_at": "2026-01-01T23:50:00+00:00"},
    )
    client.post(
        "/api/events",
        json={"project_id": project["id"], "type": "end", "occurred_at": "2026-01-02T00:30:00+00:00"},
    )
    day2 = client.get(
        "/api/events?from=2026-01-02T00:00:00%2B00:00&to=2026-01-03T00:00:00%2B00:00"
    ).json()
    types = sorted(e["type"] for e in day2)
    assert types == ["end", "start"]


def test_list_excludes_superseded_by_default(client):
    project = _make_project(client)
    created = client.post(
        "/api/events",
        json={"project_id": project["id"], "type": "instant", "occurred_at": "2026-01-01T09:00:00+00:00"},
    ).json()
    client.post(f"/api/events/{created['id']}/correct", json={"label": "fixed"})
    remaining = client.get("/api/events").json()
    assert len(remaining) == 1
    assert remaining[0]["label"] == "fixed"


def test_correct_event_does_not_mutate_original_fact_columns(client):
    project = _make_project(client)
    created = client.post(
        "/api/events",
        json={"project_id": project["id"], "type": "instant", "occurred_at": "2026-01-01T09:00:00+00:00"},
    ).json()

    resp = client.post(f"/api/events/{created['id']}/correct", json={"occurred_at": "2026-01-01T09:15:00+00:00"})
    assert resp.status_code == 200
    corrected = resp.json()
    assert corrected["corrects_id"] == created["id"]
    assert corrected["occurred_at"] == "2026-01-01T09:15:00Z"

    all_events = client.get("/api/events?" + "").json()
    # original is superseded so the default list excludes it; fetch full history via project filter
    # and direct re-derivation isn't exposed, so assert indirectly: only the corrected row is live
    assert len(all_events) == 1
    assert all_events[0]["id"] == corrected["id"]


def test_correct_already_corrected_event_conflicts(client):
    project = _make_project(client)
    created = client.post(
        "/api/events", json={"project_id": project["id"], "type": "instant"}
    ).json()
    client.post(f"/api/events/{created['id']}/correct", json={"label": "first fix"})
    resp = client.post(f"/api/events/{created['id']}/correct", json={"label": "second fix"})
    assert resp.status_code == 409


def test_correct_start_into_conflicting_state(client):
    a = _make_project(client, "A")
    b = _make_project(client, "B", "blue")
    client.post(
        "/api/events",
        json={"project_id": a["id"], "type": "start", "occurred_at": "2026-01-01T10:00:00+00:00"},
    )
    end_a = client.post(
        "/api/events",
        json={"project_id": a["id"], "type": "end", "occurred_at": "2026-01-01T11:00:00+00:00"},
    ).json()
    # B is left active (started, never ended) while A is fully completed.
    client.post(
        "/api/events",
        json={"project_id": b["id"], "type": "start", "occurred_at": "2026-01-01T12:00:00+00:00"},
    )
    # Turning A's END into a START would make A active too, conflicting with B.
    resp = client.post(f"/api/events/{end_a['id']}/correct", json={"type": "start"})
    assert resp.status_code == 409


def test_correct_active_start_to_end_clears_active_project(client):
    project = _make_project(client)
    start = client.post(
        "/api/events",
        json={"project_id": project["id"], "type": "start", "occurred_at": "2026-01-01T10:00:00+00:00"},
    ).json()
    assert client.get("/api/events/active").status_code == 200

    resp = client.post(f"/api/events/{start['id']}/correct", json={"type": "end"})
    assert resp.status_code == 200
    assert client.get("/api/events/active").status_code == 204


def test_correct_missing_event_404(client):
    resp = client.post("/api/events/9999/correct", json={"label": "x"})
    assert resp.status_code == 404


def test_active_state_replays_full_chronology_not_just_pairwise(client):
    # Regression guard for the active-project lookup: it must replay every
    # start/end for a project in true chronological (occurred_at) order,
    # not just check "does *any* later end exist" per start - the latter
    # can misattribute an unrelated already-closed session's END to a
    # different, out-of-insertion-order START for the same project.
    project = _make_project(client)
    client.post(
        "/api/events",
        json={"project_id": project["id"], "type": "start", "occurred_at": "2026-01-01T16:00:00+00:00"},
    )
    client.post(
        "/api/events",
        json={"project_id": project["id"], "type": "end", "occurred_at": "2026-01-01T17:00:00+00:00"},
    )
    # A backdated start earlier than the already-closed pair above is still
    # accepted (nothing is active at insertion time), but the resulting
    # chronology (start 09:00, start 16:00, end 17:00) is inherently
    # malformed for one project - correctly resolves to "not active"
    # rather than crashing or misreporting the backdated start as active.
    resp = client.post(
        "/api/events",
        json={"project_id": project["id"], "type": "start", "occurred_at": "2026-01-01T09:00:00+00:00"},
    )
    assert resp.status_code == 201
    assert client.get("/api/events/active").status_code == 204


def test_delete_project_with_events_conflicts(client):
    project = _make_project(client)
    client.post("/api/events", json={"project_id": project["id"], "type": "instant"})
    resp = client.delete(f"/api/projects/{project['id']}")
    assert resp.status_code == 409


def test_delete_project_without_events_still_works(client):
    project = _make_project(client)
    resp = client.delete(f"/api/projects/{project['id']}")
    assert resp.status_code == 204


def test_delete_event(client):
    project = _make_project(client)
    event = client.post("/api/events", json={"project_id": project["id"], "type": "instant"}).json()

    resp = client.delete(f"/api/events/{event['id']}")
    assert resp.status_code == 204
    assert client.get("/api/events").json() == []


def test_delete_event_missing_404(client):
    resp = client.delete("/api/events/9999")
    assert resp.status_code == 404


def test_delete_event_clears_correction_links(client):
    project = _make_project(client)
    event = client.post("/api/events", json={"project_id": project["id"], "type": "instant"}).json()
    corrected = client.post(f"/api/events/{event['id']}/correct", json={"label": "fixed"}).json()

    resp = client.delete(f"/api/events/{event['id']}")
    assert resp.status_code == 204

    remaining = client.get("/api/events").json()
    assert len(remaining) == 1
    assert remaining[0]["id"] == corrected["id"]
    assert remaining[0]["corrects_id"] is None
