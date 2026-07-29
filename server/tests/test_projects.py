def test_list_empty(client):
    resp = client.get("/api/projects")
    assert resp.status_code == 200
    assert resp.json() == []


def test_create_project(client):
    resp = client.post("/api/projects", json={"name": "Paper", "color": "lavender"})
    assert resp.status_code == 201
    body = resp.json()
    assert body["name"] == "Paper"
    assert body["color"] == "lavender"
    assert set(body.keys()) == {"id", "name", "color", "created_at"}


def test_create_empty_name_rejected(client):
    resp = client.post("/api/projects", json={"name": "   ", "color": "lavender"})
    assert resp.status_code == 422


def test_create_invalid_color_rejected(client):
    resp = client.post("/api/projects", json={"name": "Paper", "color": "purple"})
    assert resp.status_code == 422


def test_create_strips_whitespace(client):
    resp = client.post("/api/projects", json={"name": "  Paper  ", "color": "lavender"})
    assert resp.status_code == 201
    assert resp.json()["name"] == "Paper"


def test_duplicate_names_allowed(client):
    client.post("/api/projects", json={"name": "Paper", "color": "lavender"})
    resp = client.post("/api/projects", json={"name": "Paper", "color": "blue"})
    assert resp.status_code == 201


def test_list_ordered_by_created_at_asc(client):
    client.post("/api/projects", json={"name": "First", "color": "lavender"})
    client.post("/api/projects", json={"name": "Second", "color": "blue"})
    client.post("/api/projects", json={"name": "Third", "color": "green"})
    resp = client.get("/api/projects")
    names = [p["name"] for p in resp.json()]
    assert names == ["First", "Second", "Third"]


def test_update_name_and_color(client):
    created = client.post("/api/projects", json={"name": "Paper", "color": "lavender"}).json()
    resp = client.patch(f"/api/projects/{created['id']}", json={"name": "Papers", "color": "red"})
    assert resp.status_code == 200
    body = resp.json()
    assert body["name"] == "Papers"
    assert body["color"] == "red"


def test_update_partial_only_name(client):
    created = client.post("/api/projects", json={"name": "Paper", "color": "lavender"}).json()
    resp = client.patch(f"/api/projects/{created['id']}", json={"name": "Papers"})
    assert resp.status_code == 200
    body = resp.json()
    assert body["name"] == "Papers"
    assert body["color"] == "lavender"


def test_update_invalid_color_rejected(client):
    created = client.post("/api/projects", json={"name": "Paper", "color": "lavender"}).json()
    resp = client.patch(f"/api/projects/{created['id']}", json={"color": "purple"})
    assert resp.status_code == 422


def test_update_missing_project_404(client):
    resp = client.patch("/api/projects/9999", json={"name": "Ghost"})
    assert resp.status_code == 404


def test_delete_project(client):
    created = client.post("/api/projects", json={"name": "Paper", "color": "lavender"}).json()
    resp = client.delete(f"/api/projects/{created['id']}")
    assert resp.status_code == 204
    assert client.get("/api/projects").json() == []


def test_delete_missing_project_404(client):
    resp = client.delete("/api/projects/9999")
    assert resp.status_code == 404
