"""Import apply helper and import_document dry_run behavior."""

from __future__ import annotations

import asyncio
from pathlib import Path
from unittest.mock import MagicMock

import httpx
import pytest

from mcp_polarion.server import build_server
from polarion_client.client import PolarionClient
from polarion_client.credentials import EnvCredentialProvider
from polarion_client.import_apply import apply_import_preview
from polarion_client.import_files import (
    ImportDocumentPlan,
    ImportImage,
    ImportLink,
    ImportPreview,
    ImportWorkItem,
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


def test_apply_import_preview_creates_document_work_items_and_link(
    env: None, tmp_path: Path
) -> None:
    source = tmp_path / "spec.docx"
    source.write_bytes(b"original-bytes")
    wi_counter = 0
    posts: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        from urllib.parse import unquote

        path = unquote(request.url.path)
        method = request.method
        if method == "POST" and path.endswith("/documents"):
            posts.append("document")
            return httpx.Response(
                201,
                json={"data": [{"type": "documents", "id": "ELK/_default/Imported"}]},
            )
        if method == "POST" and "/documents/Imported/attachments" in path:
            posts.append("doc_attachment")
            return httpx.Response(
                201,
                json={"data": [{"type": "attachments", "id": "ELK/att-src"}]},
            )
        if method == "POST" and path.endswith("/workitems"):
            nonlocal wi_counter
            wi_counter += 1
            posts.append(f"workitem-{wi_counter}")
            return httpx.Response(
                201,
                json={
                    "data": [
                        {
                            "type": "workitems",
                            "id": f"ELK/ELK-{wi_counter}",
                            "links": {"portal": f"https://polarion.example.com/wi/{wi_counter}"},
                        }
                    ]
                },
            )
        if method == "POST" and path.endswith("/parts"):
            posts.append("part")
            return httpx.Response(
                201,
                json={"data": [{"type": "document_parts", "id": f"part-{wi_counter}"}]},
            )
        if method == "POST" and "/workitems/ELK-2/attachments" in path:
            posts.append("wi_attachment")
            return httpx.Response(204)
        if method == "POST" and path.endswith("/linkedworkitems"):
            posts.append("link")
            return httpx.Response(
                201,
                json={
                    "data": [
                        {
                            "type": "linkedworkitems",
                            "id": "ELK/ELK-1/has_parent/ELK/ELK-2",
                        }
                    ]
                },
            )
        raise AssertionError(f"unexpected {method} {path}")

    preview = ImportPreview(
        documents=[
            ImportDocumentPlan(
                module_name="Imported",
                title="Imported spec",
                home_page_content="<p>Intro</p>",
                work_items=[
                    ImportWorkItem(
                        key="wi-1",
                        wi_type="requirement",
                        title="Parent",
                        description_html="",
                        level=1,
                        parent_key=None,
                        status=None,
                        severity=None,
                        extra_attributes={},
                        reqif_id=None,
                    ),
                    ImportWorkItem(
                        key="wi-2",
                        wi_type="requirement",
                        title="Child",
                        description_html="<p>With image</p>",
                        level=2,
                        parent_key="wi-1",
                        status=None,
                        severity=None,
                        extra_attributes={},
                        reqif_id=None,
                    ),
                ],
                links=[
                    ImportLink(
                        source_key="wi-1",
                        target_key="wi-2",
                        role="has_parent",
                        relation_type="parent",
                    )
                ],
                images=[
                    ImportImage(
                        filename="diagram.png",
                        content_type="image/png",
                        data=b"\x89PNG",
                        target="work_item",
                        work_item_key="wi-2",
                    )
                ],
                warnings=["doc warning"],
            )
        ],
        source_filename="spec.docx",
        warnings=["global warning"],
    )

    client = _client(handler)
    result = apply_import_preview(
        client,
        "ELK",
        preview,
        source_path=source,
        document_type="req_specification",
        structure_link_role="has_parent",
    )

    assert result["documents"] == [
        {
            "id": "ELK/_default/Imported",
            "portal_url": (
                "https://polarion.example.com/polarion/#/project/ELK/wiki/Imported"
            ),
            "work_item_ids": ["ELK/ELK-1", "ELK/ELK-2"],
        }
    ]
    assert "global warning" in result["warnings"]
    assert "doc warning" in result["warnings"]
    assert posts == [
        "document",
        "doc_attachment",
        "workitem-1",
        "part",
        "workitem-2",
        "part",
        "wi_attachment",
        "link",
    ]


def test_import_document_dry_run_does_not_post(env: None, monkeypatch: pytest.MonkeyPatch) -> None:
    preview = ImportPreview(
        documents=[
            ImportDocumentPlan(
                module_name="Dry",
                title="Dry",
                home_page_content="",
                work_items=[],
                links=[],
                images=[],
                warnings=[],
            )
        ],
        source_filename="dry.docx",
        warnings=[],
    )

    monkeypatch.setattr(
        "mcp_polarion.tools.documents.parse_import_file",
        lambda *args, **kwargs: preview,
    )
    apply_mock = MagicMock()
    monkeypatch.setattr("mcp_polarion.tools.documents.apply_import_preview", apply_mock)

    def fail_factory() -> PolarionClient:
        raise AssertionError("dry_run must not create a Polarion client")

    mcp = build_server(fail_factory)

    async def _call() -> object:
        return await mcp.call_tool(
            "import_document",
            {
                "project_id": "ELK",
                "file_path": "/tmp/dry.docx",
                "dry_run": True,
            },
        )

    raw = asyncio.run(_call())
    structured = getattr(raw, "structured_content", raw)
    if not isinstance(structured, dict):
        raise AssertionError(f"unexpected tool result: {raw!r}")
    out = structured

    assert out["dry_run"] is True
    assert out["source_filename"] == "dry.docx"
    apply_mock.assert_not_called()
