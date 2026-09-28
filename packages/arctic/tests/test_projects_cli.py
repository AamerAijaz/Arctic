"""arctic project and work-item commands."""

from __future__ import annotations

import httpx
import pytest

from arctic.cli import run_create_work_item, run_projects
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


def test_projects_lists_ids(capsys: pytest.CaptureFixture[str], env: None) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "data": [
                    {"type": "projects", "id": "ELK", "attributes": {"name": "Elk"}},
                ]
            },
        )

    assert run_projects(_client(handler)) == 0
    assert "ELK  Elk" in capsys.readouterr().out


def test_create_work_item_dry_run_prints_body(
    capsys: pytest.CaptureFixture[str], env: None
) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise AssertionError("dry run")

    assert (
        run_create_work_item(
            _client(handler),
            project_id="ELK",
            wi_type="task",
            title="Fix login",
            description=None,
            apply=False,
        )
        == 0
    )
    out = capsys.readouterr().out
    assert "dry_run: true" in out
    assert '"type": "task"' in out


def test_create_work_item_apply_prints_id(
    capsys: pytest.CaptureFixture[str], env: None
) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            201,
            json={
                "data": [
                    {
                        "type": "workitems",
                        "id": "ELK/ELK-1",
                        "links": {"portal": "https://example.com/wi"},
                    }
                ]
            },
        )

    assert (
        run_create_work_item(
            _client(handler),
            project_id="ELK",
            wi_type="task",
            title="Fix login",
            description=None,
            apply=True,
        )
        == 0
    )
    out = capsys.readouterr().out
    assert "id: ELK/ELK-1" in out
    assert "portal_url: https://example.com/wi" in out
