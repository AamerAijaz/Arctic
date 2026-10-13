"""whoami tool payload."""

from __future__ import annotations

import httpx
import pytest

from mcp_polarion.tools.auth import whoami_payload
from polarion_client.client import PolarionClient
from polarion_client.credentials import EnvCredentialProvider
from polarion_client.errors import PolarionAuthError

TOKEN = "super-secret-token"


def test_whoami_payload(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("POLARION_URL", "https://polarion.example.com/polarion")
    monkeypatch.setenv("POLARION_TOKEN", TOKEN)

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={"data": {"type": "users", "id": "bob", "attributes": {"name": "Bob"}}},
        )

    client = PolarionClient(
        EnvCredentialProvider(),
        http=httpx.Client(transport=httpx.MockTransport(handler)),
    )
    assert whoami_payload(client) == {"id": "bob", "name": "Bob", "email": None}


def test_whoami_payload_auth_error_is_safe(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("POLARION_URL", "https://polarion.example.com")
    monkeypatch.setenv("POLARION_TOKEN", TOKEN)

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(401, json={"errors": [{"detail": TOKEN}]})

    client = PolarionClient(
        EnvCredentialProvider(),
        http=httpx.Client(transport=httpx.MockTransport(handler)),
    )
    with pytest.raises(PolarionAuthError) as caught:
        whoami_payload(client)
    assert TOKEN not in str(caught.value)
