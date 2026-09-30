"""arctic work-item list and link commands."""

from __future__ import annotations

import httpx
import pytest

from arctic.cli import run_link, run_link_roles, run_unlink, run_work_items
from polarion_client.client import PolarionClient
from polarion_client.credentials import EnvCredentialProvider

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


def test_work_items_lists_ids(capsys: pytest.CaptureFixture[str], env: None) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
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
                        },
                    }
                ]
            },
        )

    assert run_work_items(_client(handler), "ELK", query="type:requirement") == 0
    out = capsys.readouterr().out
    assert "ELK/ELK-12" in out
    assert "requirement" in out
    assert "Login must SSO" in out


def test_link_roles_lists_ids(capsys: pytest.CaptureFixture[str], env: None) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "data": {
                    "type": "enumerations",
                    "id": "ELK/~/workitem-link-role/~",
                    "attributes": {
                        "options": [
                            {"id": "implements", "name": "implements"},
                        ]
                    },
                }
            },
        )

    assert run_link_roles(_client(handler), "ELK") == 0
    assert "implements" in capsys.readouterr().out


def test_link_dry_run_prints_body(
    capsys: pytest.CaptureFixture[str], env: None
) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise AssertionError("dry run")

    assert (
        run_link(
            _client(handler),
            project_id="ELK",
            work_item_id="ELK-42",
            target_work_item_id="ELK-12",
            role="implements",
            target_project_id=None,
            apply=False,
        )
        == 0
    )
    out = capsys.readouterr().out
    assert "dry_run: true" in out
    assert '"role": "implements"' in out
    assert "ELK/ELK-12" in out


def test_unlink_dry_run_prints_path(
    capsys: pytest.CaptureFixture[str], env: None
) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise AssertionError("dry run")

    assert (
        run_unlink(
            _client(handler),
            project_id="ELK",
            work_item_id="ELK-42",
            target_work_item_id="ELK-12",
            role="implements",
            target_project_id=None,
            apply=False,
        )
        == 0
    )
    out = capsys.readouterr().out
    assert "dry_run: true" in out
    assert "linkedworkitems/implements/ELK/ELK-12" in out
