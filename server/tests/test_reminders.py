def test_create_reminder(client):
    resp = client.post(
        "/api/reminders",
        json={"remind_at": "2026-08-10T09:00:00Z", "message": "10 дней до дедлайна"},
    )
    assert resp.status_code == 201
    body = resp.json()
    assert body["message"] == "10 дней до дедлайна"
    assert body["notified"] is False
    assert set(body.keys()) == {"id", "remind_at", "message", "notified", "created_at"}


def test_create_reminder_blank_message_rejected(client):
    resp = client.post("/api/reminders", json={"remind_at": "2026-08-10T09:00:00Z", "message": "   "})
    assert resp.status_code == 422


def test_list_reminders_sorted_by_remind_at(client):
    client.post("/api/reminders", json={"remind_at": "2026-08-15T09:00:00Z", "message": "later"})
    client.post("/api/reminders", json={"remind_at": "2026-08-10T09:00:00Z", "message": "earlier"})
    resp = client.get("/api/reminders")
    assert resp.status_code == 200
    messages = [r["message"] for r in resp.json()]
    assert messages == ["earlier", "later"]


def test_list_reminders_filtered_by_range(client):
    client.post("/api/reminders", json={"remind_at": "2026-08-01T09:00:00Z", "message": "before"})
    client.post("/api/reminders", json={"remind_at": "2026-08-20T09:00:00Z", "message": "inside"})
    client.post("/api/reminders", json={"remind_at": "2026-09-01T09:00:00Z", "message": "after"})
    resp = client.get("/api/reminders", params={"from": "2026-08-05T00:00:00Z", "to": "2026-08-25T00:00:00Z"})
    assert resp.status_code == 200
    messages = [r["message"] for r in resp.json()]
    assert messages == ["inside"]


def test_delete_reminder(client):
    created = client.post(
        "/api/reminders", json={"remind_at": "2026-08-10T09:00:00Z", "message": "delete me"}
    ).json()
    resp = client.delete(f"/api/reminders/{created['id']}")
    assert resp.status_code == 204
    assert client.get("/api/reminders").json() == []


def test_delete_missing_reminder_404(client):
    resp = client.delete("/api/reminders/9999")
    assert resp.status_code == 404
