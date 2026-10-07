"""Polarion project and work-item client."""

from __future__ import annotations

import json
from urllib.parse import unquote

import httpx
import pytest

from polarion_client.client import PolarionClient
from polarion_client.credentials import EnvCredentialProvider
from polarion_client.errors import PolarionApiError, PolarionError
from polarion_client.models import (
    CreatedWorkItem,
    UpdatedWorkItem,
    WorkItemCreatePreview,
    WorkItemUpdatePreview,
)

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
        assert (
            request.url.params["fields[workitems]"]
            == "id,title,type,status,description,assignee"
        )
        assert request.url.params["include"] == "assignee"
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
                        "description": {"type": "text/html", "value": "<p>SSO</p>"},
                    },
                    "relationships": {
                        "assignee": {
                            "data": [{"type": "users", "id": "alice"}],
                        }
                    },
                }
            },
        )

    item = _client(handler).get_work_item("ELK", "ELK-42")
    assert item.id == "ELK/ELK-42"
    assert item.title == "Fix login"
    assert item.type == "task"
    assert item.status == "open"
    assert item.description == "<p>SSO</p>"
    assert item.assignee_ids == ("alice",)
    assert item.to_dict()["assignee_ids"] == ["alice"]


def test_list_work_items_omits_description_fields(env: None) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        fields = request.url.params["fields[workitems]"]
        assert fields == "id,title,type,status"
        assert "description" not in fields
        assert "assignee" not in fields
        return httpx.Response(200, json={"data": []})

    items = _client(handler).list_work_items("ELK")
    assert items == []


def test_create_blocked_by_allowlist(env: None, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("POLARION_PROJECT_ALLOWLIST", "ELK")

    def handler(request: httpx.Request) -> httpx.Response:
        raise AssertionError("must not call Polarion")

    with pytest.raises(PolarionApiError, match="ALLOWLIST"):
        _client(handler).create_work_item("OTHER", "task", "Nope", dry_run=False)


def test_update_work_item_dry_run_does_not_patch(env: None) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise AssertionError("dry_run must not call Polarion")

    result = _client(handler).update_work_item(
        "ELK",
        "ELK-42",
        title="Fix login again",
        description="<p>Updated</p>",
        status="in_progress",
    )
    assert isinstance(result, WorkItemUpdatePreview)
    assert result.project_id == "ELK"
    assert result.work_item_id == "ELK-42"
    assert result.body == {
        "data": {
            "type": "workitems",
            "id": "ELK/ELK-42",
            "attributes": {
                "title": "Fix login again",
                "description": {"type": "text/html", "value": "<p>Updated</p>"},
                "status": "in_progress",
            },
        }
    }


def test_update_work_item_apply(env: None) -> None:
    seen: dict[str, object] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["method"] = request.method
        seen["path"] = unquote(request.url.path)
        seen["body"] = request.read()
        return httpx.Response(204)

    result = _client(handler).update_work_item(
        "ELK", "ELK-42", title="Renamed", dry_run=False
    )
    assert isinstance(result, UpdatedWorkItem)
    assert result.id == "ELK/ELK-42"
    assert seen["method"] == "PATCH"
    assert str(seen["path"]).endswith("/projects/ELK/workitems/ELK-42")
    body = seen["body"]
    assert isinstance(body, bytes)
    assert b'"title":"Renamed"' in body
    assert b'"id":"ELK/ELK-42"' in body
    assert TOKEN not in body.decode()


def test_update_work_item_requires_fields(env: None) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise AssertionError("must not call Polarion")

    with pytest.raises(PolarionError, match="at least one"):
        _client(handler).update_work_item("ELK", "ELK-42", dry_run=False)


def test_update_work_item_change_type_to_apply(env: None) -> None:
    seen: dict[str, object] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["path"] = unquote(request.url.path)
        seen["query"] = dict(request.url.params)
        seen["body"] = json.loads(request.read().decode())
        return httpx.Response(204)

    result = _client(handler).update_work_item(
        "ELK", "ELK-5", change_type_to="hardware", dry_run=False
    )
    assert isinstance(result, UpdatedWorkItem)
    assert result.id == "ELK/ELK-5"
    assert str(seen["path"]).endswith("/projects/ELK/workitems/ELK-5")
    assert seen["query"] == {"changeTypeTo": "hardware"}
    assert seen["body"]["data"]["attributes"] == {}


def test_update_work_item_not_found(env: None) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            404, json={"errors": [{"status": "404", "title": "Not Found"}]}
        )

    with pytest.raises(PolarionApiError, match="Work item not found: ELK/ELK-99"):
        _client(handler).update_work_item(
            "ELK", "ELK-99", title="Nope", dry_run=False
        )


def test_update_blocked_by_allowlist(env: None, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("POLARION_PROJECT_ALLOWLIST", "ELK")

    def handler(request: httpx.Request) -> httpx.Response:
        raise AssertionError("must not call Polarion")

    with pytest.raises(PolarionApiError, match="ALLOWLIST"):
        _client(handler).update_work_item(
            "OTHER", "OTHER-1", title="Nope", dry_run=False
        )
