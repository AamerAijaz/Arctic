"""Polarion document and attachment client."""

from __future__ import annotations

from urllib.parse import unquote

import httpx
import pytest

from polarion_client.client import PolarionClient, document_portal_url
from polarion_client.credentials import EnvCredentialProvider
from polarion_client.errors import PolarionApiError
from polarion_client.models import (
    CreatedDocument,
    CreatedDocumentWorkItem,
    DocumentCreatePreview,
    DocumentWorkItemCreatePreview,
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


def test_document_portal_url_omits_default_space() -> None:
    root = "https://polarion.example.com/polarion/rest/v1"
    assert document_portal_url(root, "drivepilot", "_default", "Fleet") == (
        "https://polarion.example.com/polarion/#/project/drivepilot/wiki/Fleet"
    )
    assert document_portal_url(root, "drivepilot", "Specs", "Fleet") == (
        "https://polarion.example.com/polarion/#/project/drivepilot/wiki/Specs/Fleet"
    )


def test_list_documents(env: None) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        path = unquote(request.url.path)
        assert path.endswith("/projects/ELK/spaces/_default/documents")
        assert request.url.params["fields[documents]"] == "id,moduleName,title,type,status"
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
                            "type": "req",
                            "status": "published",
                        },
                    }
                ]
            },
        )

    docs = _client(handler).list_documents("ELK")
    assert len(docs) == 1
    assert docs[0].module_name == "Specs"
    assert docs[0].portal_url == document_portal_url(
        "https://polarion.example.com/polarion/rest/v1",
        "ELK",
        "_default",
        "Specs",
    )


def test_get_document_portal_from_links(env: None) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "data": {
                    "type": "documents",
                    "id": "ELK/_default/Specs",
                    "attributes": {
                        "moduleName": "Specs",
                        "homePageContent": {
                            "type": "text/html",
                            "value": "<p>Hello <b>world</b> and more text</p>",
                        },
                    },
                    "links": {"portal": "https://polarion.example.com/polarion/#/wiki"},
                }
            },
        )

    doc = _client(handler).get_document("ELK", "Specs")
    assert doc.portal_url == "https://polarion.example.com/polarion/#/wiki"
    assert doc.content_summary == "Hello world and more text"


def test_get_document_not_found(env: None) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            404, json={"errors": [{"status": "404", "title": "Not Found"}]}
        )

    with pytest.raises(PolarionApiError, match="Document not found: ELK/_default/missing"):
        _client(handler).get_document("ELK", "missing")


def test_create_document_dry_run(env: None) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise AssertionError("dry_run must not call Polarion")

    result = _client(handler).create_document(
        "ELK",
        "NewSpec",
        title="New spec",
        document_type="req",
        structure_link_role="parent",
        home_page_content="<p>Intro</p>",
    )
    assert isinstance(result, DocumentCreatePreview)
    assert result.body["data"][0]["attributes"]["moduleName"] == "NewSpec"
    assert "homePageContent" in result.body["data"][0]["attributes"]


def test_create_document_omits_empty_home_page(env: None) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise AssertionError("dry_run must not call Polarion")

    result = _client(handler).create_document(
        "ELK",
        "Bare",
        document_type="req",
        structure_link_role="parent",
        home_page_content="",
    )
    assert isinstance(result, DocumentCreatePreview)
    assert "homePageContent" not in result.body["data"][0]["attributes"]


def test_create_document_apply_fallback_portal(env: None) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.method == "POST"
        return httpx.Response(
            201,
            json={
                "data": [
                    {
                        "type": "documents",
                        "id": "ELK/_default/NewSpec",
                    }
                ]
            },
        )

    result = _client(handler).create_document(
        "ELK",
        "NewSpec",
        document_type="req",
        structure_link_role="parent",
        dry_run=False,
    )
    assert isinstance(result, CreatedDocument)
    assert result.portal_url == document_portal_url(
        "https://polarion.example.com/polarion/rest/v1",
        "ELK",
        "_default",
        "NewSpec",
    )


def test_list_document_parts_strips_html(env: None) -> None:
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
                            "level": 1,
                            "headingText": "Title",
                            "content": {"type": "text/html", "value": "<p>Body</p>"},
                        },
                    }
                ]
            },
        )

    parts = _client(handler).list_document_parts("ELK", "Specs")
    assert parts[0].text == "Body"
    assert parts[0].level == 1


def test_create_document_work_item_dry_run(env: None) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise AssertionError("dry_run must not call Polarion")

    result = _client(handler).create_document_work_item(
        "ELK",
        "Specs",
        "requirement",
        "REQ-1",
        description="<p>Desc</p>",
        status="open",
    )
    assert isinstance(result, DocumentWorkItemCreatePreview)
    wi = result.work_item_body["data"][0]
    assert wi["relationships"]["module"]["data"]["id"] == "ELK/_default/Specs"
    assert wi["attributes"]["status"] == "open"


def test_create_document_work_item_apply(env: None) -> None:
    calls = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        path = unquote(request.url.path)
        if path.endswith("/workitems"):
            return httpx.Response(
                201,
                json={
                    "data": [
                        {
                            "type": "workitems",
                            "id": "ELK/ELK-7",
                            "links": {"portal": "https://polarion.example.com/wi/7"},
                        }
                    ]
                },
            )
        if path.endswith("/parts"):
            body = request.read().decode()
            assert "ELK/ELK-7" in body
            return httpx.Response(
                201,
                json={"data": [{"type": "document_parts", "id": "part-99"}]},
            )
        raise AssertionError(f"unexpected path {path}")

    result = _client(handler).create_document_work_item(
        "ELK", "Specs", "requirement", "REQ-1", dry_run=False
    )
    assert isinstance(result, CreatedDocumentWorkItem)
    assert result.id == "ELK/ELK-7"
    assert result.part_id == "part-99"
    assert result.portal_url == "https://polarion.example.com/wi/7"
    assert calls == 2


def test_upload_document_attachment_multipart(env: None) -> None:
    seen: dict[str, object] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["content_type"] = request.headers.get("content-type", "")
        seen["method"] = request.method
        seen["body"] = request.read()
        return httpx.Response(
            201,
            json={"data": [{"type": "attachments", "id": "ELK/att-1"}]},
        )

    att_id = _client(handler).upload_document_attachment(
        "ELK", "Specs", "notes.txt", b"hello", content_type="text/plain"
    )
    assert att_id == "ELK/att-1"
    assert seen["method"] == "POST"
    ct = str(seen["content_type"])
    assert "multipart/form-data" in ct
    assert "application/json" not in ct
    body = seen["body"]
    assert b'name="resource"' in body
    assert b"document_attachments" in body
    assert b"notes.txt" in body
    assert b'name="files"' in body


def test_import_word_document_dry_run(env: None) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise AssertionError("dry_run must not call Polarion")

    result = _client(handler).import_word_document(
        "ELK",
        "spec.docx",
        module_name="Spec",
        document_type="req_specification",
        title="Spec",
    )
    assert result["dry_run"] is True
    assert result["parameters"]["documentName"] == "Spec"
    assert "importWordDocument" in result["path"]


def test_import_word_document_apply_waits_for_job(env: None, tmp_path) -> None:
    docx = tmp_path / "spec.docx"
    docx.write_bytes(b"PK\x03\x04fake")
    seen: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        seen.append(f"{request.method} {path}")
        if request.method == "POST" and path.endswith("/importWordDocument"):
            body = request.read()
            assert b'name="parameters"' in body
            assert b'name="file"' in body
            assert b"spec.docx" in body
            return httpx.Response(
                202,
                json={
                    "data": {
                        "type": "jobs",
                        "id": "job-1",
                        "attributes": {"state": "RUNNING"},
                    }
                },
            )
        if request.method == "GET" and path.endswith("/jobs/job-1"):
            return httpx.Response(
                200,
                json={
                    "data": {
                        "type": "jobs",
                        "id": "job-1",
                        "attributes": {
                            "state": "FINISHED",
                            "status": {"type": "OK"},
                        },
                    }
                },
            )
        if request.method == "GET" and path.endswith("/documents/Spec"):
            return httpx.Response(
                200,
                json={
                    "data": {
                        "type": "documents",
                        "id": "ELK/_default/Spec",
                        "attributes": {
                            "moduleName": "Spec",
                            "title": "Spec",
                            "type": "req_specification",
                            "status": "draft",
                            "homePageContent": {
                                "type": "text/html",
                                "value": "<h1>Spec</h1>",
                            },
                        },
                    }
                },
            )
        raise AssertionError(f"unexpected {request.method} {path}")

    result = _client(handler).import_word_document(
        "ELK",
        str(docx),
        module_name="Spec",
        document_type="req_specification",
        title="Spec",
        dry_run=False,
        interval=0.01,
    )
    assert result["job"]["id"] == "job-1"
    assert result["document"]["module_name"] == "Spec"
    assert any("importWordDocument" in item for item in seen)


def test_documents_blocked_by_allowlist(env: None, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("POLARION_PROJECT_ALLOWLIST", "ELK")

    def handler(request: httpx.Request) -> httpx.Response:
        raise AssertionError("must not call Polarion")

    with pytest.raises(PolarionApiError, match="ALLOWLIST"):
        _client(handler).list_documents("OTHER")
