"""arctic whoami command."""

from __future__ import annotations

import httpx
import pytest

from arctic.cli import main, run_whoami
from polarion_client.client import PolarionClient
from polarion_client.credentials import EnvCredentialProvider

TOKEN = "super-secret-token"


def _client(status: int, body: dict) -> PolarionClient:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(status, json=body)

    return PolarionClient(
        EnvCredentialProvider(),
        http=httpx.Client(transport=httpx.MockTransport(handler)),
    )


def test_whoami_prints_user(capsys: pytest.CaptureFixture[str], monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("POLARION_URL", "https://polarion.example.com")
    monkeypatch.setenv("POLARION_TOKEN", TOKEN)
    client = _client(
        200,
        {"data": {"type": "users", "id": "alice", "attributes": {"name": "Alice", "email": "a@ex.com"}}},
    )
    assert run_whoami(client) == 0
    out = capsys.readouterr().out
    assert "id: alice" in out
    assert "name: Alice" in out
    assert "email: a@ex.com" in out
    assert TOKEN not in out


def test_whoami_missing_credentials(
    capsys: pytest.CaptureFixture[str],
    monkeypatch: pytest.MonkeyPatch,
    tmp_path,
) -> None:
    monkeypatch.chdir(tmp_path)
    monkeypatch.delenv("POLARION_URL", raising=False)
    monkeypatch.delenv("POLARION_TOKEN", raising=False)
    assert main(["whoami"]) == 2
    err = capsys.readouterr().err
    assert "POLARION_TOKEN" in err
