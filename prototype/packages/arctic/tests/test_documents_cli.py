"""arctic document commands."""

from __future__ import annotations

from pathlib import Path
from urllib.parse import unquote

import httpx
import pytest

from arctic.cli import (
    run_create_document,
    run_create_document_work_item,
    run_document,
    run_document_parts,
    run_documents,
    run_import_document,
)
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


def test_documents_lists_ids(capsys: pytest.CaptureFixture[str], env: None) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        path = unquote(request.url.path)
        assert path.endswith("/projects/ELK/spaces/_default/documents")
        return httpx.Response(
            200,
            json={
                "data": [
                    {
                        "type": "documents",
                        "id": "ELK/_default/Specs",
                        "attributes": {
                            "moduleName": "Specs",
                            "title": "Specifications",
                        },
                    }
                ]
            },
        )

    assert run_documents(_client(handler), "ELK") == 0
    assert "ELK/_default/Specs  Specs  Specifications" in capsys.readouterr().out


def test_document_shows_fields(capsys: pytest.CaptureFixture[str], env: None) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "data": {
                    "type": "documents",
                    "id": "ELK/_default/Specs",
                    "attributes": {"moduleName": "Specs", "title": "Specifications"},
                    "links": {"portal": "https://polarion.example.com/wiki"},
                }
            },
        )

    assert run_document(_client(handler), "ELK", "Specs") == 0
    out = capsys.readouterr().out
    assert "id: ELK/_default/Specs" in out
    assert "portal_url: https://polarion.example.com/wiki" in out


def test_create_document_dry_run_prints_body(
    capsys: pytest.CaptureFixture[str], env: None
) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise AssertionError("dry run")

    assert (
        run_create_document(
            _client(handler),
            project_id="ELK",
            module_name="NewSpec",
            document_type="req_specification",
            structure_link_role="has_parent",
            title="New spec",
            home_page_content=None,
            space_id="_default",
            apply=False,
        )
        == 0
    )
    out = capsys.readouterr().out
    assert "dry_run: true" in out
    assert '"moduleName": "NewSpec"' in out


def test_create_document_apply_prints_portal(
    capsys: pytest.CaptureFixture[str], env: None
) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            201,
            json={"data": [{"type": "documents", "id": "ELK/_default/NewSpec"}]},
        )

    assert (
        run_create_document(
            _client(handler),
            project_id="ELK",
            module_name="NewSpec",
            document_type="req_specification",
            structure_link_role="has_parent",
            title=None,
            home_page_content=None,
            space_id="_default",
            apply=True,
        )
        == 0
    )
    out = capsys.readouterr().out
    assert "id: ELK/_default/NewSpec" in out
    assert "portal_url:" in out


def test_document_parts_lists(capsys: pytest.CaptureFixture[str], env: None) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        path = unquote(request.url.path)
        assert path.endswith("/projects/ELK/spaces/_default/documents/Specs/parts")
        return httpx.Response(
            200,
            json={
                "data": [
                    {
                        "type": "document_parts",
                        "id": "part-1",
                        "attributes": {
                            "type": "heading",
                            "headingText": "Intro",
                        },
                    }
                ]
            },
        )

    assert run_document_parts(_client(handler), "ELK", "Specs") == 0
    assert "part-1  heading  Intro" in capsys.readouterr().out


def test_create_document_work_item_dry_run(
    capsys: pytest.CaptureFixture[str], env: None
) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise AssertionError("dry run")

    assert (
        run_create_document_work_item(
            _client(handler),
            project_id="ELK",
            document_name="Specs",
            wi_type="requirement",
            title="REQ-1",
            description=None,
            space_id="_default",
            apply=False,
        )
        == 0
    )
    out = capsys.readouterr().out
    assert "dry_run: true" in out
    assert "ELK/_default/Specs" in out


def test_import_document_dry_run(
    capsys: pytest.CaptureFixture[str], env: None, tmp_path: Path
) -> None:
    from docx import Document as DocxDocument

    docx_path = tmp_path / "sample.docx"
    doc = DocxDocument()
    doc.add_heading("Requirement A", level=1)
    doc.save(str(docx_path))

    def handler(request: httpx.Request) -> httpx.Response:
        raise AssertionError("dry run must not call Polarion")

    assert (
        run_import_document(
            _client(handler),
            project_id="ELK",
            file_path=str(docx_path),
            module_name=None,
            title=None,
            document_type="req_specification",
            work_item_type="requirement",
            structure_link_role="has_parent",
            space_id="_default",
            apply=False,
        )
        == 0
    )
    out = capsys.readouterr().out
    assert "dry_run: true" in out
    assert "Requirement A" in out
