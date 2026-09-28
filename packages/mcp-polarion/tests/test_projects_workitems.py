"""Polarion project and work-item client."""

from __future__ import annotations

from urllib.parse import unquote

import httpx
import pytest

from polarion_client.client import PolarionClient
from polarion_client.credentials import EnvCredentialProvider
from polarion_client.errors import PolarionApiError
from polarion_client.models import CreatedWorkItem, WorkItemCreatePreview

TOKEN = "super-secret-token"


def _client(handler) -> PolarionClient:
    return PolarionClient(
        EnvCredentialProvider(),
        http=httpx.Client(transport=httpx.MockTransport(handler)),
    )


@pytest.fixture
def env(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("POLARION_URL", "https://polarion.example.com")
    monkeypatch.setenv("POLARION_TOKEN", TOKEN)
    monkeypatch.delenv("POLARION_PROJECT_ALLOWLIST", raising=False)


def test_list_projects(env: None) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path.endswith("/projects")
        assert request.url.params["fields[projects]"] == "id,name,description,active,trackerPrefix"
        return httpx.Response(
            200,
            json={
                "data": [
                    {
                        "type": "projects",
                        "id": "ELK",
                        "attributes": {
                            "name": "Elk",
                            "description": {"type": "text/plain", "value": "Demo"},
                            "active": True,
                            "trackerPrefix": "ELK",
                        },
                    }
                ]
            },
        )

    projects = _client(handler).list_projects()
    assert len(projects) == 1
    assert projects[0].id == "ELK"
    assert projects[0].name == "Elk"
    assert projects[0].description == "Demo"
    assert projects[0].active is True
    assert projects[0].tracker_prefix == "ELK"


def test_list_projects_allowlist(env: None, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("POLARION_PROJECT_ALLOWLIST", "ELK")

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "data": [
                    {"type": "projects", "id": "ELK", "attributes": {"name": "Elk"}},
                    {"type": "projects", "id": "OTHER", "attributes": {"name": "Other"}},
                ]
            },
        )

    projects = _client(handler).list_projects()
    assert [project.id for project in projects] == ["ELK"]


def test_get_project_not_found(env: None) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            404, json={"errors": [{"status": "404", "title": "Not Found"}]}
        )

    with pytest.raises(PolarionApiError, match="Project not found: missing"):
        _client(handler).get_project("missing")


def test_create_work_item_dry_run_does_not_post(env: None) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise AssertionError("dry_run must not call Polarion")

    result = _client(handler).create_work_item(
        "ELK", "task", "Fix login", description="<p>SSO timeout</p>"
    )
    assert isinstance(result, WorkItemCreatePreview)
    assert result.project_id == "ELK"
    assert result.body == {
        "data": [
            {
                "type": "workitems",
                "attributes": {
                    "type": "task",
                    "title": "Fix login",
                    "description": {"type": "text/html", "value": "<p>SSO timeout</p>"},
                },
            }
        ]
    }


def test_create_work_item_apply(env: None) -> None:
    seen: dict[str, object] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["method"] = request.method
        seen["path"] = unquote(request.url.path)
        seen["body"] = request.read()
        return httpx.Response(
            201,
            json={
                "data": [
                    {
                        "type": "workitems",
                        "id": "ELK/ELK-42",
                        "links": {
                            "portal": "https://polarion.example.com/polarion/redirect/project/ELK/workitem?id=ELK-42"
                        },
                    }
                ]
            },
        )

    result = _client(handler).create_work_item(
        "ELK", "task", "Fix login", dry_run=False
    )
    assert isinstance(result, CreatedWorkItem)
    assert result.id == "ELK/ELK-42"
    assert result.portal_url is not None
    assert seen["method"] == "POST"
    assert str(seen["path"]).endswith("/projects/ELK/workitems")
    body = seen["body"]
    assert isinstance(body, bytes)
    assert b'"type":"task"' in body
    assert TOKEN not in body.decode()


def test_create_work_item_bad_type_redacts_token(env: None) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            400,
            json={"errors": [{"detail": f"Unknown type Bearer {TOKEN}"}]},
        )

    with pytest.raises(PolarionApiError, match="redacted") as caught:
        _client(handler).create_work_item("ELK", "nope", "Title", dry_run=False)
    assert TOKEN not in str(caught.value)


def test_get_work_item(env: None) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert unquote(request.url.path).endswith("/projects/ELK/workitems/ELK-42")
        return httpx.Response(
            200,
            json={
                "data": {
                    "type": "workitems",
                    "id": "ELK/ELK-42",
                    "attributes": {
                        "title": "Fix login",
                        "type": "task",
                        "status": "open",
                    },
                }
            },
        )

    item = _client(handler).get_work_item("ELK", "ELK-42")
    assert item.id == "ELK/ELK-42"
    assert item.title == "Fix login"
    assert item.type == "task"
    assert item.status == "open"


def test_create_blocked_by_allowlist(env: None, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("POLARION_PROJECT_ALLOWLIST", "ELK")

    def handler(request: httpx.Request) -> httpx.Response:
        raise AssertionError("must not call Polarion")

    with pytest.raises(PolarionApiError, match="ALLOWLIST"):
        _client(handler).create_work_item("OTHER", "task", "Nope", dry_run=False)
