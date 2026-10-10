"""arctic promote / requirement-block commands."""

from __future__ import annotations

import json
from pathlib import Path

import httpx
import pytest

from arctic.cli import (
    main,
    run_convert_headings,
    run_promote_requirements,
    run_requirement_blocks,
)
from polarion_client.client import PolarionClient
from polarion_client.credentials import EnvCredentialProvider
from polarion_client.requirement_blocks import RequirementBlock

TOKEN = "super-secret-token"


def _client_raises() -> PolarionClient:
    def handler(request: httpx.Request) -> httpx.Response:
        raise AssertionError(f"Unexpected HTTP: {request.url}")

    return PolarionClient(
        EnvCredentialProvider(),
        http=httpx.Client(transport=httpx.MockTransport(handler)),
    )


@pytest.fixture
def env(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("POLARION_URL", "https://polarion.example.com")
    monkeypatch.setenv("POLARION_TOKEN", TOKEN)


def test_requirement_blocks_prints_json(
    capsys: pytest.CaptureFixture[str],
    env: None,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    client = _client_raises()
    block = RequirementBlock(
        section="Functional",
        title="REQ-1",
        heading_part_id="p1",
        work_item_id="ELK/wi-1",
        text_part_ids=["t1"],
        lines=["Global ID: G1"],
        description="Global ID: G1",
        description_html="<p>Global ID: G1</p>",
    )
    monkeypatch.setattr(
        client,
        "list_requirement_blocks",
        lambda *a, **k: [block],
    )
    assert (
        run_requirement_blocks(
            client,
            "ELK",
            "Specs",
            sections=["Functional"],
        )
        == 0
    )
    data = json.loads(capsys.readouterr().out)
    assert data == [block.to_dict()]


def test_promote_requirements_dry_run(
    capsys: pytest.CaptureFixture[str],
    env: None,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    client = _client_raises()
    captured: dict[str, object] = {}

    def fake_promote(*_a, **kwargs):
        captured.update(kwargs)
        return {"dry_run": True, "skipped": [], "converted": []}

    monkeypatch.setattr(client, "promote_document_requirements", fake_promote)
    assert (
        run_promote_requirements(
            client,
            project_id="ELK",
            document_name="Specs",
            sections=["Functional"],
            wi_type="hardware",
            space_id="_default",
            assign=True,
            delete_source_text=False,
            user_ids=None,
            apply=False,
        )
        == 0
    )
    assert captured["dry_run"] is True
    assert json.loads(capsys.readouterr().out)["dry_run"] is True


def test_promote_requirements_apply_via_main(
    env: None,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: dict[str, object] = {}

    def fake_promote(self, *_a, **kwargs):
        captured.update(kwargs)
        return {"dry_run": False, "skipped": [], "converted": []}

    monkeypatch.setattr(PolarionClient, "promote_document_requirements", fake_promote)
    assert (
        main(
            [
                "promote-requirements",
                "--project",
                "ELK",
                "--document",
                "Specs",
                "--sections",
                "Functional",
                "--type",
                "hardware",
                "--apply",
            ]
        )
        == 0
    )
    assert captured["dry_run"] is False
    assert captured["delete_source_text"] is True


def test_promote_requirements_keep_source_text_flag(
    env: None,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: dict[str, object] = {}

    def fake_promote(self, *_a, **kwargs):
        captured.update(kwargs)
        return {"dry_run": True, "skipped": [], "converted": []}

    monkeypatch.setattr(PolarionClient, "promote_document_requirements", fake_promote)
    assert (
        main(
            [
                "promote-requirements",
                "--project",
                "ELK",
                "--document",
                "Specs",
                "--sections",
                "Functional",
                "--type",
                "hardware",
                "--keep-source-text",
            ]
        )
        == 0
    )
    assert captured["delete_source_text"] is False


def test_convert_headings_reads_items_file(
    capsys: pytest.CaptureFixture[str],
    env: None,
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    client = _client_raises()
    items = [{"part_id": "h1", "description": "<p>x</p>"}]
    items_path = tmp_path / "items.json"
    items_path.write_text(json.dumps(items), encoding="utf-8")
    def fake_convert(_project_id, _document_name, items, _wi_type, **kwargs):
        return {"dry_run": kwargs["dry_run"], "items": items}

    monkeypatch.setattr(client, "convert_headings_to_work_items", fake_convert)
    assert (
        run_convert_headings(
            client,
            project_id="ELK",
            document_name="Specs",
            wi_type="hardware",
            items_file=str(items_path),
            space_id="_default",
            apply=False,
        )
        == 0
    )
    out = json.loads(capsys.readouterr().out)
    assert out["items"] == items
    assert out["dry_run"] is True


def test_assign_round_robin_parses_ids(
    env: None,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: dict[str, object] = {}

    def fake_assign(self, _project_id, work_item_ids, *, user_ids=None, dry_run=True):
        captured["work_item_ids"] = work_item_ids
        captured["dry_run"] = dry_run
        return {"dry_run": dry_run, "assignments": []}

    monkeypatch.setattr(PolarionClient, "assign_work_items_round_robin", fake_assign)
    assert (
        main(
            [
                "assign-round-robin",
                "--project",
                "ELK",
                "--ids",
                "DP-1, DP-2",
            ]
        )
        == 0
    )
    assert captured["work_item_ids"] == ["DP-1", "DP-2"]
    assert captured["dry_run"] is True
