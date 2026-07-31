from app import notifications


def test_register_device_token(client):
    resp = client.post("/api/notifications/register", json={"token": "abc123"})
    assert resp.status_code == 204


def test_register_device_token_idempotent(client):
    client.post("/api/notifications/register", json={"token": "abc123"})
    resp = client.post("/api/notifications/register", json={"token": "abc123"})
    assert resp.status_code == 204


def test_register_blank_token_rejected(client):
    resp = client.post("/api/notifications/register", json={"token": "   "})
    assert resp.status_code == 422


def test_unregister_device_token(client):
    client.post("/api/notifications/register", json={"token": "abc123"})
    resp = client.post("/api/notifications/unregister", json={"token": "abc123"})
    assert resp.status_code == 204


def test_unregister_missing_token_is_a_noop(client):
    resp = client.post("/api/notifications/unregister", json={"token": "never-registered"})
    assert resp.status_code == 204


def test_send_push_noop_without_firebase_configured():
    # No FCM_SERVICE_ACCOUNT_FILE is set in the test environment - this must
    # never raise, and must report 0 sends rather than pretending to succeed.
    sent = notifications.send_push(title="t", body="b", data={"type": "start"})
    assert sent == 0
