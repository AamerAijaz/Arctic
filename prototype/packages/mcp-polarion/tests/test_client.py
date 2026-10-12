"""Polarion GET /user client."""

from __future__ import annotations

from pathlib import Path

import httpx
import pytest

from polarion_client.client import PolarionClient
from polarion_client.credentials import EnvCredentialProvider, normalize_rest_root
from polarion_client.errors import (
    MissingCredentialsError,
    PolarionApiError,
    PolarionAuthError,
    PolarionUnavailableError,
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


def test_dotenv_fills_missing_environment(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    monkeypatch.chdir(tmp_path)
    monkeypatch.delenv("POLARION_URL", raising=False)
    monkeypatch.delenv("POLARION_TOKEN", raising=False)
    (tmp_path / ".env").write_text(
        "POLARION_URL=https://from-dotenv.example\nPOLARION_TOKEN=from-dotenv\n",
        encoding="utf-8",
    )
    creds = EnvCredentialProvider().get()
    assert creds.rest_root == "https://from-dotenv.example/polarion/rest/v1"
    assert creds.token == "from-dotenv"


def test_normalize_rest_root_accepts_common_forms() -> None:
    assert (
        normalize_rest_root("https://polarion.example.com")
        == "https://polarion.example.com/polarion/rest/v1"
    )
    assert (
        normalize_rest_root("https://polarion.example.com/polarion/")
        == "https://polarion.example.com/polarion/rest/v1"
    )
    assert (
        normalize_rest_root("https://polarion.example.com/polarion/rest/v1")
        == "https://polarion.example.com/polarion/rest/v1"
    )


def test_missing_credentials(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    monkeypatch.chdir(tmp_path)
    monkeypatch.delenv("POLARION_URL", raising=False)
    monkeypatch.delenv("POLARION_TOKEN", raising=False)
    with pytest.raises(MissingCredentialsError, match="POLARION_URL"):
        EnvCredentialProvider().get()


def test_get_current_user(env: None) -> None:
    seen: dict[str, str] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["url"] = str(request.url)
        seen["authorization"] = request.headers["Authorization"]
        return httpx.Response(
            200,
            json={
                "data": {
                    "type": "users",
                    "id": "alice",
                    "attributes": {"id": "alice", "name": "Alice", "email": "alice@example.com"},
                }
            },
        )

    user = _client(handler).get_current_user()
    assert user.id == "alice"
    assert user.name == "Alice"
    assert user.email == "alice@example.com"
    assert seen["url"] == "https://polarion.example.com/polarion/rest/v1/user"
    assert seen["authorization"] == f"Bearer {TOKEN}"


def test_unauthorized_does_not_leak_token(env: None) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            401,
            json={"errors": [{"status": "401", "title": "Unauthorized", "detail": TOKEN}]},
        )

    with pytest.raises(PolarionAuthError) as caught:
        _client(handler).get_current_user()
    assert TOKEN not in str(caught.value)


def test_rest_disabled(env: None) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(503, json={"errors": [{"title": "Service Unavailable"}]})

    with pytest.raises(PolarionUnavailableError, match="not enabled"):
        _client(handler).get_current_user()


def test_api_error_redacts_token(env: None) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            400,
            json={"errors": [{"detail": f"bad field Bearer {TOKEN}"}]},
        )

    with pytest.raises(PolarionApiError, match="redacted") as caught:
        _client(handler).get_current_user()
    assert TOKEN not in str(caught.value)
