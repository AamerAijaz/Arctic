"""Polarion project users and work-item assignees."""

from __future__ import annotations

import json
from urllib.parse import unquote

import httpx
import pytest

from polarion_client.client import PolarionClient
from polarion_client.credentials import EnvCredentialProvider
from polarion_client.errors import PolarionApiError
from polarion_client.models import AssignedWorkItem, WorkItemAssignPreview

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


def test_list_project_users_filters_role(env: None) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path.endswith("/users")
        assert request.url.params["include"] == "projectRoles"
        assert "projectRoles" in request.url.params["fields[users]"]
        return httpx.Response(
            200,
            json={
                "data": [
                    {
                        "type": "users",
                        "id": "alice",
                        "attributes": {"name": "Alice", "email": "a@example.com"},
                        "relationships": {
                            "projectRoles": {
                                "data": [{"type": "projectroles", "id": "ELK/project_assignable"}]
                            }
                        },
                    },
                    {
                        "type": "users",
                        "id": "bob",
                        "attributes": {"name": "Bob", "email": "b@example.com"},
                        "relationships": {
                            "projectRoles": {
                                "data": [{"type": "projectroles", "id": "ELK/project_admin"}]
                            }
                        },
                    },
                ]
            },
        )

    users = _client(handler).list_project_users("ELK")
    assert [user.id for user in users] == ["alice"]
    assert users[0].email == "a@example.com"


def test_list_project_users_role_suffix_match(env: None) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "data": [
                    {
                        "type": "users",
                        "id": "carol",
                        "attributes": {"name": "Carol"},
                        "relationships": {
                            "projectRoles": {
                                "data": [
                                    {"type": "projectroles", "id": "ELK/custom/project_assignable"}
                                ]
                            }
                        },
                    }
                ]
            },
        )

    users = _client(handler).list_project_users("ELK", role="project_assignable")
    assert [user.id for user in users] == ["carol"]


def test_list_project_users_allowlist(env: None, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("POLARION_PROJECT_ALLOWLIST", "ELK")

    def handler(request: httpx.Request) -> httpx.Response:
        raise AssertionError("must not call Polarion")

    with pytest.raises(PolarionApiError, match="ALLOWLIST"):
        _client(handler).list_project_users("OTHER")


def test_assign_work_item_dry_run(env: None) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise AssertionError("dry_run must not call Polarion")

    result = _client(handler).assign_work_item("ELK", "ELK-1", ["alice", "bob"])
    assert isinstance(result, WorkItemAssignPreview)
    assignees = result.body["data"]["relationships"]["assignee"]["data"]
    assert assignees == [
        {"type": "users", "id": "alice"},
        {"type": "users", "id": "bob"},
    ]


def test_assign_work_item_clear_assignees(env: None) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise AssertionError("dry_run must not call Polarion")

    result = _client(handler).assign_work_item("ELK", "ELK-1", [])
    assert isinstance(result, WorkItemAssignPreview)
    assert result.body["data"]["relationships"]["assignee"]["data"] == []


def test_assign_work_item_apply(env: None) -> None:
    seen: dict[str, object] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["method"] = request.method
        seen["path"] = unquote(request.url.path)
        seen["body"] = json.loads(request.read().decode())
        return httpx.Response(204)

    result = _client(handler).assign_work_item(
        "ELK", "ELK-1", ["alice"], dry_run=False
    )
    assert isinstance(result, AssignedWorkItem)
    assert result.id == "ELK/ELK-1"
    assert seen["method"] == "PATCH"
    assert str(seen["path"]).endswith("/projects/ELK/workitems/ELK-1")
    body = seen["body"]
    assert body["data"]["id"] == "ELK/ELK-1"


def test_assign_work_item_not_found(env: None) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            404, json={"errors": [{"status": "404", "title": "Not Found"}]}
        )

    with pytest.raises(PolarionApiError, match="Work item not found: ELK/ELK-99"):
        _client(handler).assign_work_item("ELK", "ELK-99", ["alice"], dry_run=False)


def test_assign_error_redacts_token(env: None) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            400,
            json={"errors": [{"detail": f"bad assignee Bearer {TOKEN}"}]},
        )

    with pytest.raises(PolarionApiError, match="redacted") as caught:
        _client(handler).assign_work_item("ELK", "ELK-1", ["alice"], dry_run=False)
    assert TOKEN not in str(caught.value)
