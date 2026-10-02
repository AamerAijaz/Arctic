"""arctic project users and assignee commands."""

from __future__ import annotations

import httpx
import pytest

from arctic.cli import run_assign_work_item, run_project_users
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


def test_project_users_lists_ids(capsys: pytest.CaptureFixture[str], env: None) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path.endswith("/users")
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
                                "data": [
                                    {"type": "projectroles", "id": "ELK/project_assignable"}
                                ]
                            }
                        },
                    }
                ]
            },
        )

    assert run_project_users(_client(handler), "ELK") == 0
    out = capsys.readouterr().out
    assert "alice  Alice  a@example.com" in out


def test_assign_work_item_dry_run_prints_body(
    capsys: pytest.CaptureFixture[str], env: None
) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise AssertionError("dry run")

    assert (
        run_assign_work_item(
            _client(handler),
            project_id="ELK",
            work_item_id="ELK-1",
            user_ids=["alice", "bob"],
            apply=False,
        )
        == 0
    )
    out = capsys.readouterr().out
    assert "dry_run: true" in out
    assert '"id": "alice"' in out
    assert '"id": "bob"' in out


def test_assign_work_item_apply_prints_id(
    capsys: pytest.CaptureFixture[str], env: None
) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(204)

    assert (
        run_assign_work_item(
            _client(handler),
            project_id="ELK",
            work_item_id="ELK-1",
            user_ids=["alice"],
            apply=True,
        )
        == 0
    )
    assert "id: ELK/ELK-1" in capsys.readouterr().out
