"""Work-item list and link client."""

from __future__ import annotations

from urllib.parse import unquote

import httpx
import pytest

from polarion_client.client import PolarionClient
from polarion_client.credentials import EnvCredentialProvider
from polarion_client.errors import PolarionApiError
from polarion_client.models import (
    CreatedWorkItemLink,
    DeletedWorkItemLink,
    WorkItemLinkDeletePreview,
    WorkItemLinkPreview,
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


def test_list_work_items_query(env: None) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert unquote(request.url.path).endswith("/projects/ELK/workitems")
        assert request.url.params["query"] == "type:requirement"
        fields = request.url.params["fields[workitems]"]
        assert fields == "id,title,type,status"
        assert "description" not in fields
        return httpx.Response(
            200,
            json={
                "data": [
                    {
                        "type": "workitems",
                        "id": "ELK/ELK-12",
                        "attributes": {
                            "title": "Login must SSO",
                            "type": "requirement",
                            "status": "draft",
                        },
                    }
                ]
            },
        )

    items = _client(handler).list_work_items("ELK", query="type:requirement")
    assert len(items) == 1
    assert items[0].id == "ELK/ELK-12"
    assert items[0].type == "requirement"
    assert items[0].title == "Login must SSO"


def test_list_work_items_blocked_by_allowlist(
    env: None, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("POLARION_PROJECT_ALLOWLIST", "ELK")

    def handler(request: httpx.Request) -> httpx.Response:
        raise AssertionError("must not call Polarion")

    with pytest.raises(PolarionApiError, match="ALLOWLIST"):
        _client(handler).list_work_items("OTHER")


def test_list_link_roles(env: None) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert "/enumerations/~/workitem-link-role/~" in unquote(request.url.path)
        return httpx.Response(
            200,
            json={
                "data": {
                    "type": "enumerations",
                    "id": "ELK/~/workitem-link-role/~",
                    "attributes": {
                        "options": [
                            {
                                "id": "implements",
                                "name": "implements",
                                "oppositeName": "is implemented by",
                                "linkRules": [
                                    {
                                        "fromTypes": ["task"],
                                        "toTypes": ["requirement"],
                                    }
                                ],
                            }
                        ]
                    },
                }
            },
        )

    roles = _client(handler).list_link_roles("ELK")
    assert len(roles) == 1
    assert roles[0].id == "implements"
    assert roles[0].opposite_name == "is implemented by"
    assert roles[0].link_rules == [
        {"fromTypes": ["task"], "toTypes": ["requirement"]}
    ]


def test_list_work_item_links_includes_titles(env: None) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path.endswith("/linkedworkitems")
        assert request.url.params["include"] == "workItem"
        return httpx.Response(
            200,
            json={
                "data": [
                    {
                        "type": "linkedworkitems",
                        "id": "ELK/ELK-42/implements/ELK/ELK-12",
                        "attributes": {"role": "implements"},
                        "relationships": {
                            "workItem": {
                                "data": {
                                    "type": "workitems",
                                    "id": "ELK/ELK-12",
                                }
                            }
                        },
                    }
                ],
                "included": [
                    {
                        "type": "workitems",
                        "id": "ELK/ELK-12",
                        "attributes": {
                            "title": "Login must SSO",
                            "type": "requirement",
                            "status": "draft",
                        },
                    }
                ],
            },
        )

    links = _client(handler).list_work_item_links("ELK", "ELK-42")
    assert len(links) == 1
    assert links[0].role == "implements"
    assert links[0].target_id == "ELK/ELK-12"
    assert links[0].target_title == "Login must SSO"


def test_list_work_item_backlinks(env: None) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path.endswith("/backlinkedworkitems")
        return httpx.Response(
            200,
            json={
                "data": [
                    {
                        "type": "linkedworkitems",
                        "id": "ELK/ELK-42/implements/ELK/ELK-12",
                        "attributes": {"role": "implements"},
                        "relationships": {
                            "workItem": {
                                "data": {
                                    "type": "workitems",
                                    "id": "ELK/ELK-42",
                                }
                            }
                        },
                    }
                ],
                "included": [
                    {
                        "type": "workitems",
                        "id": "ELK/ELK-42",
                        "attributes": {"title": "Fix login", "type": "task"},
                    }
                ],
            },
        )

    links = _client(handler).list_work_item_backlinks("ELK", "ELK-12")
    assert links[0].target_id == "ELK/ELK-42"
    assert links[0].target_title == "Fix login"


def test_create_work_item_link_dry_run(env: None) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise AssertionError("dry_run must not call Polarion")

    result = _client(handler).create_work_item_link(
        "ELK", "ELK-42", "ELK-12", "implements"
    )
    assert isinstance(result, WorkItemLinkPreview)
    assert result.body == {
        "data": [
            {
                "type": "linkedworkitems",
                "attributes": {"role": "implements"},
                "relationships": {
                    "workItem": {
                        "data": {
                            "type": "workitems",
                            "id": "ELK/ELK-12",
                        }
                    }
                },
            }
        ]
    }


def test_create_work_item_link_apply(env: None) -> None:
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
                        "type": "linkedworkitems",
                        "id": "ELK/ELK-42/implements/ELK/ELK-12",
                    }
                ]
            },
        )

    result = _client(handler).create_work_item_link(
        "ELK", "ELK-42", "ELK-12", "implements", dry_run=False
    )
    assert isinstance(result, CreatedWorkItemLink)
    assert result.id == "ELK/ELK-42/implements/ELK/ELK-12"
    assert seen["method"] == "POST"
    assert str(seen["path"]).endswith(
        "/projects/ELK/workitems/ELK-42/linkedworkitems"
    )
    body = seen["body"]
    assert isinstance(body, bytes)
    assert b'"role":"implements"' in body
    assert TOKEN not in body.decode()


def test_create_work_item_link_target_allowlist(
    env: None, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("POLARION_PROJECT_ALLOWLIST", "ELK")

    def handler(request: httpx.Request) -> httpx.Response:
        raise AssertionError("must not call Polarion")

    with pytest.raises(PolarionApiError, match="ALLOWLIST"):
        _client(handler).create_work_item_link(
            "ELK",
            "ELK-42",
            "OTHER-1",
            "relates_to",
            target_project_id="OTHER",
            dry_run=False,
        )


def test_delete_work_item_link_dry_run(env: None) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise AssertionError("dry_run must not call Polarion")

    result = _client(handler).delete_work_item_link(
        "ELK", "ELK-42", "ELK-12", "implements"
    )
    assert isinstance(result, WorkItemLinkDeletePreview)
    assert result.path.endswith(
        "/projects/ELK/workitems/ELK-42/linkedworkitems/implements/ELK/ELK-12"
    )


def test_delete_work_item_link_apply(env: None) -> None:
    seen: dict[str, object] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["method"] = request.method
        seen["path"] = unquote(request.url.path)
        return httpx.Response(204)

    result = _client(handler).delete_work_item_link(
        "ELK", "ELK-42", "ELK-12", "implements", dry_run=False
    )
    assert isinstance(result, DeletedWorkItemLink)
    assert result.id == "ELK/ELK-42/implements/ELK/ELK-12"
    assert seen["method"] == "DELETE"
    assert str(seen["path"]).endswith(
        "/projects/ELK/workitems/ELK-42/linkedworkitems/implements/ELK/ELK-12"
    )


def test_delete_work_item_link_not_found(env: None) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            404, json={"errors": [{"status": "404", "title": "Not Found"}]}
        )

    with pytest.raises(PolarionApiError, match="Link not found"):
        _client(handler).delete_work_item_link(
            "ELK", "ELK-42", "ELK-99", "implements", dry_run=False
        )
