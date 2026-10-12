"""Replay the language-neutral fixtures in <repo>/fixtures against the Python client."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import httpx
import pytest

from polarion_client import errors
from polarion_client.client import PolarionClient, redact
from polarion_client.credentials import PolarionCredentials, normalize_rest_root

FIXTURES = Path(__file__).resolve().parents[4] / "fixtures" / "polarion"
BASE_URL = "https://polarion.example.com"
TOKEN = "test-token"

SCENARIOS = sorted(p for p in FIXTURES.glob("*/*.json"))


class StaticCredentialProvider:
    def get(self) -> PolarionCredentials:
        return PolarionCredentials(rest_root=normalize_rest_root(BASE_URL), token=TOKEN)


def _load(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def _plain(result: Any) -> Any:
    if isinstance(result, list):
        return [_plain(item) for item in result]
    if hasattr(result, "to_dict"):
        return result.to_dict()
    return result


class Replay:
    def __init__(self, exchanges: list[dict[str, Any]]) -> None:
        self.exchanges = exchanges
        self.index = 0

    def __call__(self, request: httpx.Request) -> httpx.Response:
        assert self.index < len(self.exchanges), (
            f"unexpected extra request {request.method} {request.url}"
        )
        exchange = self.exchanges[self.index]
        self.index += 1
        expected = exchange["request"]
        where = f"exchange {self.index}"

        assert request.url.scheme == "https", where
        assert request.url.host == "polarion.example.com", where
        assert request.method == expected["method"], where
        raw_path = request.url.raw_path.decode("ascii").split("?", 1)[0]
        assert raw_path == expected["path"], where
        query: dict[str, str] = {}
        for key, value in request.url.params.multi_items():
            assert key not in query, f"{where}: repeated query key {key}"
            query[key] = value
        assert query == expected.get("query", {}), where

        assert request.headers["Authorization"] == f"Bearer {TOKEN}", where
        assert request.headers["Accept"] == "application/json", where
        if "body" in expected:
            assert request.headers["Content-Type"] == "application/json", where
            assert json.loads(request.content) == expected["body"], where
        else:
            assert request.content == b"", where

        response = exchange["response"]
        if "body" in response:
            return httpx.Response(response["status"], json=response["body"])
        return httpx.Response(response["status"])


@pytest.mark.parametrize(
    "path", SCENARIOS, ids=[f"{p.parent.name}/{p.stem}" for p in SCENARIOS]
)
def test_scenario(path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    fixture = _load(path)
    assert fixture["name"] == path.stem
    monkeypatch.delenv("POLARION_PROJECT_ALLOWLIST", raising=False)
    for key, value in fixture.get("env", {}).items():
        monkeypatch.setenv(key, value)

    replay = Replay(fixture["exchanges"])
    client = PolarionClient(
        StaticCredentialProvider(),
        http=httpx.Client(transport=httpx.MockTransport(replay)),
    )
    call = fixture["call"]
    operation = getattr(client, call["operation"])
    expect = fixture["expect"]

    if "error" in expect:
        want = expect["error"]
        with pytest.raises(errors.PolarionError) as caught:
            operation(**call["args"])
        exc = caught.value
        assert type(exc).__name__ == want["type"]
        if "status" in want:
            assert getattr(exc, "status_code", None) == want["status"]
        message = str(exc)
        if "message_contains" in want:
            assert want["message_contains"] in message
        if "message_excludes" in want:
            assert want["message_excludes"] not in message
    else:
        result = operation(**call["args"])
        assert _plain(result) == expect["result"]

    assert replay.index == len(fixture["exchanges"]), "not all exchanges were used"


def test_every_area_has_scenarios() -> None:
    areas = {p.parent.name for p in SCENARIOS}
    assert {"auth", "projects", "workitems", "links", "users"} <= areas


URL_CASES = _load(FIXTURES / "url_normalization.json")["cases"]


@pytest.mark.parametrize("case", URL_CASES, ids=[c["name"] for c in URL_CASES])
def test_url_normalization(case: dict[str, Any]) -> None:
    if "error" in case:
        with pytest.raises(errors.PolarionError) as caught:
            normalize_rest_root(case["input"])
        assert type(caught.value).__name__ == case["error"]["type"]
    else:
        assert normalize_rest_root(case["input"]) == case["expected"]


REDACTION_CASES = _load(FIXTURES / "redaction.json")["cases"]


@pytest.mark.parametrize(
    "case", REDACTION_CASES, ids=[c["name"] for c in REDACTION_CASES]
)
def test_redaction(case: dict[str, Any]) -> None:
    assert redact(case["text"], case["token"]) == case["expected"]
