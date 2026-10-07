"""Polarion document and attachment client."""

from __future__ import annotations

import json
from urllib.parse import unquote

import httpx
import pytest

from polarion_client.client import PolarionClient, document_portal_url
from polarion_client.credentials import EnvCredentialProvider
from polarion_client.errors import PolarionApiError, PolarionError
from polarion_client.models import (
    ConvertedHeadingWorkItem,
    ConvertHeadingPreview,
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
        assert request.url.params["fields[document_parts]"] == (
            "id,type,level,headingText,content,workItem"
        )
        assert request.url.params["include"] == "workItem"
        return httpx.Response(
            200,
            json={
                "data": [
                    {
                        "type": "document_parts",
                        "id": "ELK/_default/Specs/heading_ELK-5",
                        "attributes": {
                            "type": "heading",
                            "level": 1,
                            "headingText": "Title",
                            "content": {"type": "text/html", "value": "<p>Body</p>"},
                        },
                        "relationships": {
                            "workItem": {
                                "data": {"type": "workitems", "id": "ELK/ELK-5"}
                            }
                        },
                    }
                ]
            },
        )

    parts = _client(handler).list_document_parts("ELK", "Specs")
    assert parts[0].text == "Body"
    assert parts[0].level == 1
    assert parts[0].work_item_id == "ELK/ELK-5"


def test_convert_heading_to_work_item_dry_run(env: None) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        if request.method == "PATCH":
            raise AssertionError("dry_run must not patch Polarion")
        path = unquote(request.url.path)
        assert path.endswith("/parts/heading_ELK-5")
        return httpx.Response(
            200,
            json={
                "data": {
                    "type": "document_parts",
                    "id": "ELK/_default/Specs/heading_ELK-5",
                    "attributes": {"type": "heading", "headingText": "Wi-Fi"},
                    "relationships": {
                        "workItem": {
                            "data": {"type": "workitems", "id": "ELK/ELK-5"}
                        }
                    },
                }
            },
        )

    result = _client(handler).convert_heading_to_work_item(
        "ELK",
        "Specs",
        "heading_ELK-5",
        "hardware",
        description="<p>Radio</p>",
    )
    assert isinstance(result, ConvertHeadingPreview)
    assert result.work_item_id == "ELK-5"
    assert result.change_type_to == "hardware"
    assert result.body["data"]["attributes"]["description"]["value"] == "<p>Radio</p>"


def test_convert_heading_to_work_item_apply(env: None) -> None:
    seen: dict[str, object] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        path = unquote(request.url.path)
        if request.method == "GET" and path.endswith("/parts/heading_ELK-5"):
            return httpx.Response(
                200,
                json={
                    "data": {
                        "type": "document_parts",
                        "id": "ELK/_default/Specs/heading_ELK-5",
                        "attributes": {"type": "heading"},
                        "relationships": {
                            "workItem": {
                                "data": {"type": "workitems", "id": "ELK/ELK-5"}
                            }
                        },
                    }
                },
            )
        seen["method"] = request.method
        seen["path"] = path
        seen["query"] = dict(request.url.params)
        seen["body"] = json.loads(request.read().decode())
        return httpx.Response(204)

    result = _client(handler).convert_heading_to_work_item(
        "ELK",
        "Specs",
        "ELK/_default/Specs/heading_ELK-5",
        "hardware",
        dry_run=False,
    )
    assert isinstance(result, ConvertedHeadingWorkItem)
    assert result.id == "ELK/ELK-5"
    assert result.part_id == "ELK/_default/Specs/heading_ELK-5"
    assert result.change_type_to == "hardware"
    assert seen["method"] == "PATCH"
    assert str(seen["path"]).endswith("/projects/ELK/workitems/ELK-5")
    assert seen["query"] == {"changeTypeTo": "hardware"}
    assert seen["body"]["data"]["id"] == "ELK/ELK-5"
    assert seen["body"]["data"]["attributes"] == {}


def test_convert_heading_requires_work_item(env: None) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "data": {
                    "type": "document_parts",
                    "id": "ELK/_default/Specs/heading_1",
                    "attributes": {"type": "heading", "headingText": "Intro"},
                }
            },
        )

    with pytest.raises(PolarionError, match="has no work item"):
        _client(handler).convert_heading_to_work_item(
            "ELK", "Specs", "heading_1", "requirement", dry_run=False
        )


def test_move_document_part_dry_run(env: None) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise AssertionError("dry_run must not call Polarion")

    result = _client(handler).move_document_part(
        "ELK",
        "Specs",
        "ELK/_default/Specs/workitem_ELK-7",
        after="ELK/_default/Specs/heading_1",
    )
    assert result["dry_run"] is True
    assert result["path"].endswith("/parts/workitem_ELK-7/actions/move")
    assert result["body"]["after"] == "ELK/_default/Specs/heading_1"


def test_move_document_part_apply(env: None) -> None:
    seen: dict[str, object] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["method"] = request.method
        seen["path"] = unquote(request.url.path)
        seen["body"] = json.loads(request.read().decode())
        return httpx.Response(204)

    result = _client(handler).move_document_part(
        "ELK",
        "Specs",
        "workitem_ELK-7",
        after="ELK/_default/Specs/heading_1",
        dry_run=False,
    )
    assert result["moved"] is True
    assert seen["method"] == "POST"
    assert str(seen["path"]).endswith("/parts/workitem_ELK-7/actions/move")
    assert seen["body"] == {"after": "ELK/_default/Specs/heading_1"}


def test_delete_document_parts_apply(env: None) -> None:
    seen: dict[str, object] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["method"] = request.method
        seen["path"] = unquote(request.url.path)
        seen["body"] = json.loads(request.read().decode())
        return httpx.Response(204)

    result = _client(handler).delete_document_parts(
        "ELK",
        "Specs",
        ["ELK/_default/Specs/polarion_4"],
        dry_run=False,
    )
    assert result["deleted"] == ["ELK/_default/Specs/polarion_4"]
    assert seen["method"] == "DELETE"
    assert str(seen["path"]).endswith("/documents/Specs/parts")
    assert seen["body"]["data"][0]["id"] == "ELK/_default/Specs/polarion_4"


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


def test_create_document_work_item_places_under_parent(env: None) -> None:
    seen: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        path = unquote(request.url.path)
        seen.append(f"{request.method} {path}")
        if path.endswith("/workitems"):
            return httpx.Response(
                201,
                json={
                    "data": [
                        {
                            "type": "workitems",
                            "id": "ELK/ELK-8",
                            "links": {"portal": "https://polarion.example.com/wi/8"},
                        }
                    ]
                },
            )
        if path.endswith("/parts"):
            return httpx.Response(
                201,
                json={
                    "data": [
                        {
                            "type": "document_parts",
                            "id": "ELK/_default/Specs/workitem_ELK-8",
                        }
                    ]
                },
            )
        if path.endswith("/parts/workitem_ELK-8/actions/move"):
            body = json.loads(request.read().decode())
            assert body["parent"] == "ELK/_default/Specs/heading_1"
            assert body["before"] == "ELK/_default/Specs/heading_wifi"
            return httpx.Response(204)
        raise AssertionError(f"unexpected path {path}")

    result = _client(handler).create_document_work_item(
        "ELK",
        "Specs",
        "hardware",
        "Wi-Fi",
        parent="ELK/_default/Specs/heading_1",
        before="ELK/_default/Specs/heading_wifi",
        dry_run=False,
    )
    assert isinstance(result, CreatedDocumentWorkItem)
    assert result.part_id == "ELK/_default/Specs/workitem_ELK-8"
    assert any(path.endswith("/actions/move") for path in seen)


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
