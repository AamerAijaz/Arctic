"""Tests for requirement promotion helpers (no HTTP)."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

import pytest

from polarion_client.errors import PolarionError
from polarion_client.models import (
    AssignedWorkItem,
    ConvertedHeadingWorkItem,
    ConvertHeadingPreview,
    CreatedDocumentWorkItem,
    Document,
    DocumentPart,
    DocumentWorkItemCreatePreview,
    ProjectUser,
    WorkItem,
    WorkItemAssignPreview,
)
from polarion_client.promote import (
    assign_work_items_round_robin,
    convert_headings_to_work_items,
    list_all_document_parts,
    promote_document_requirements,
    resolve_round_robin_users,
)

def _heading(part_id: str, level: int, text: str, work_item_id: str | None = None) -> DocumentPart:
    return DocumentPart(
        id=part_id,
        part_type="heading",
        level=level,
        heading_text=text,
        work_item_id=work_item_id,
    )


def _normal(part_id: str, text: str) -> DocumentPart:
    return DocumentPart(id=part_id, part_type="normal", text=text)


def _workitem(part_id: str, work_item_id: str) -> DocumentPart:
    return DocumentPart(
        id=part_id, part_type="workitem", work_item_id=work_item_id
    )


@dataclass
class FakeClient:
    parts_pages: list[list[DocumentPart]] = field(default_factory=list)
    parts_calls: list[dict[str, Any]] = field(default_factory=list)
    convert_calls: list[dict[str, Any]] = field(default_factory=list)
    create_calls: list[dict[str, Any]] = field(default_factory=list)
    delete_calls: list[dict[str, Any]] = field(default_factory=list)
    assign_calls: list[dict[str, Any]] = field(default_factory=list)
    project_users: list[ProjectUser] = field(default_factory=list)
    work_items: dict[str, WorkItem] = field(default_factory=dict)
    convert_fail_part_ids: set[str] = field(default_factory=set)
    create_fail_previous_parts: set[str] = field(default_factory=set)
    portal_url: str | None = "https://polarion.example/doc"
    forbid_mutations: bool = False

    def list_document_parts(
        self,
        project_id: str,
        document_name: str,
        *,
        space_id: str = "_default",
        page_size: int = 100,
        page_number: int = 1,
    ) -> list[DocumentPart]:
        self.parts_calls.append(
            {
                "project_id": project_id,
                "document_name": document_name,
                "space_id": space_id,
                "page_size": page_size,
                "page_number": page_number,
            }
        )
        idx = page_number - 1
        if idx < len(self.parts_pages):
            return self.parts_pages[idx]
        return []

    def convert_heading_to_work_item(
        self,
        project_id: str,
        document_name: str,
        part_id: str,
        wi_type: str,
        *,
        space_id: str = "_default",
        description: str | None = None,
        dry_run: bool = True,
    ) -> ConvertedHeadingWorkItem | ConvertHeadingPreview:
        if self.forbid_mutations and not dry_run:
            raise AssertionError("mutating convert forbidden")
        self.convert_calls.append(
            {
                "part_id": part_id,
                "wi_type": wi_type,
                "description": description,
                "dry_run": dry_run,
            }
        )
        if part_id in self.convert_fail_part_ids:
            raise PolarionError(f"convert failed for {part_id}")
        if dry_run:
            return ConvertHeadingPreview(
                project_id=project_id,
                document_name=document_name,
                part_id=part_id,
                work_item_id=f"WI-{part_id}",
                change_type_to=wi_type,
                body={},
            )
        return ConvertedHeadingWorkItem(
            id=f"ELK/WI-{part_id}",
            part_id=part_id,
            change_type_to=wi_type,
        )

    def create_document_work_item(
        self,
        project_id: str,
        document_name: str,
        wi_type: str,
        title: str,
        *,
        space_id: str = "_default",
        description: str | None = None,
        previous_part: str | None = None,
        dry_run: bool = True,
        **kwargs: Any,
    ) -> CreatedDocumentWorkItem | DocumentWorkItemCreatePreview:
        if self.forbid_mutations and not dry_run:
            raise AssertionError("mutating create forbidden")
        self.create_calls.append(
            {
                "title": title,
                "wi_type": wi_type,
                "description": description,
                "previous_part": previous_part,
                "dry_run": dry_run,
            }
        )
        if previous_part in self.create_fail_previous_parts:
            raise PolarionError(f"create failed for {previous_part}")
        if dry_run:
            return DocumentWorkItemCreatePreview(
                project_id=project_id,
                work_item_body={},
                part_body={},
                previous_part=previous_part,
            )
        local = (previous_part or title).replace("/", "-")
        return CreatedDocumentWorkItem(
            id=f"ELK/WI-{local}",
            part_id=f"workitem_{local}",
        )

    def delete_document_parts(
        self,
        project_id: str,
        document_name: str,
        part_ids: list[str],
        *,
        space_id: str = "_default",
        dry_run: bool = True,
    ) -> dict[str, Any]:
        if self.forbid_mutations and not dry_run:
            raise AssertionError("mutating delete forbidden")
        self.delete_calls.append(
            {
                "part_ids": list(part_ids),
                "dry_run": dry_run,
            }
        )
        return {"deleted": part_ids}

    def assign_work_item(
        self,
        project_id: str,
        work_item_id: str,
        user_ids: list[str],
        *,
        dry_run: bool = True,
    ) -> AssignedWorkItem | WorkItemAssignPreview:
        if self.forbid_mutations and not dry_run:
            raise AssertionError("mutating assign forbidden")
        self.assign_calls.append(
            {
                "work_item_id": work_item_id,
                "user_ids": user_ids,
                "dry_run": dry_run,
            }
        )
        if dry_run:
            return WorkItemAssignPreview(
                project_id=project_id,
                work_item_id=work_item_id,
                body={},
            )
        return AssignedWorkItem(id=f"{project_id}/{work_item_id}")

    def list_project_users(self, project_id: str, **kwargs: Any) -> list[ProjectUser]:
        return list(self.project_users)

    def get_work_item(self, project_id: str, work_item_id: str) -> WorkItem:
        return self.work_items[work_item_id]

    def get_document(
        self,
        project_id: str,
        document_name: str,
        *,
        space_id: str = "_default",
    ) -> Document:
        return Document(
            id=f"{project_id}/{space_id}/{document_name}",
            module_name=document_name,
            portal_url=self.portal_url,
        )


def test_list_all_document_parts_paginates() -> None:
    page1 = [_normal(f"n{i}", "x") for i in range(100)]
    page2 = [_normal("n100", "y"), _normal("n101", "z")]
    client = FakeClient(parts_pages=[page1, page2])
    parts = list_all_document_parts(client, "ELK", "Doc")
    assert len(parts) == 102
    assert client.parts_calls[0]["page_number"] == 1
    assert client.parts_calls[1]["page_number"] == 2
    assert len(client.parts_calls) == 2


def test_convert_dry_run_skips_delete_and_plans_deletes() -> None:
    client = FakeClient()
    items = [
        {"part_id": "h1", "description": "<p>x</p>", "delete_part_ids": ["t1", "t2"]},
    ]
    result = convert_headings_to_work_items(
        client, "ELK", "Doc", items, "hardware", dry_run=True
    )
    assert result["dry_run"] is True
    assert len(client.convert_calls) == 1
    assert client.convert_calls[0]["dry_run"] is True
    assert client.delete_calls == []
    row = result["results"][0]
    assert row["ok"] is True
    assert row["delete_part_ids"] == ["t1", "t2"]


def test_convert_apply_delete_per_success_not_on_failure() -> None:
    client = FakeClient(convert_fail_part_ids={"h2"})
    items = [
        {"part_id": "h1", "delete_part_ids": ["t1"]},
        {"part_id": "h2", "delete_part_ids": ["t2"]},
        {"part_id": "h3", "delete_part_ids": ["t3"]},
    ]
    result = convert_headings_to_work_items(
        client, "ELK", "Doc", items, "hardware", dry_run=False
    )
    assert [r["part_id"] for r in result["results"]] == ["h1", "h2", "h3"]
    assert result["results"][0]["ok"] is True
    assert result["results"][1]["ok"] is False
    assert result["results"][2]["ok"] is True
    assert client.delete_calls == [
        {"part_ids": ["t1"], "dry_run": False},
        {"part_ids": ["t3"], "dry_run": False},
    ]


def test_resolve_round_robin_users_sorted_and_empty_raises() -> None:
    client = FakeClient(
        project_users=[
            ProjectUser(id="z"),
            ProjectUser(id="a"),
            ProjectUser(id="m"),
        ]
    )
    assert resolve_round_robin_users(client, "ELK", None) == ["a", "m", "z"]
    assert resolve_round_robin_users(client, "ELK", ["bob"]) == ["bob"]
    with pytest.raises(PolarionError, match="non-empty"):
        resolve_round_robin_users(client, "ELK", [])
    empty = FakeClient(project_users=[])
    with pytest.raises(PolarionError, match="No assignable users"):
        resolve_round_robin_users(empty, "ELK", None)


def test_assign_work_items_round_robin_order() -> None:
    client = FakeClient(
        project_users=[
            ProjectUser(id="z"),
            ProjectUser(id="a"),
            ProjectUser(id="m"),
        ]
    )
    result = assign_work_items_round_robin(
        client, "ELK", ["WI-1", "WI-2", "WI-3", "WI-4"], dry_run=True
    )
    assert result["users"] == ["a", "m", "z"]
    assert [a["user_id"] for a in result["assignments"]] == [
        "a",
        "m",
        "z",
        "a",
    ]


def _promotable_parts() -> list[DocumentPart]:
    return [
        _heading("h-func", 1, "Functional"),
        _heading("h-wifi", 2, "Wi-Fi"),
        _normal("t1", "Global ID: G-001"),
        _normal("t2", "Project ID: P-100"),
        _normal("t3", "Type: heading"),
        _normal("t4", "The device shall support WPA3."),
        _heading("h-brand", 2, "Brand Marking"),
        _normal("t5", "Logo only, no global id."),
        _heading("h-done", 2, "Already Hardware", work_item_id="ELK/WI-heading"),
        _normal("t6", "Global ID: G-2"),
        _normal("t7", ""),
        _normal("t8", "Body for done item."),
        _workitem("w-done", "ELK/WI-done"),
    ]


def test_promote_dry_run_skips_and_plans() -> None:
    client = FakeClient(
        parts_pages=[_promotable_parts()],
        project_users=[
            ProjectUser(id="z"),
            ProjectUser(id="a"),
        ],
        forbid_mutations=True,
    )
    result = promote_document_requirements(
        client,
        "ELK",
        "Doc",
        ["Functional"],
        "hardware",
        dry_run=True,
        delete_source_text=False,
    )
    assert result["dry_run"] is True
    assert result["planned_count"] == 1
    reasons = {s["reason"] for s in result["skipped"]}
    assert "not_promotable" in reasons
    assert "already_marked" in reasons
    assert client.convert_calls == []
    assert all(c["dry_run"] for c in client.create_calls)
    assert client.create_calls[0]["previous_part"] == "h-wifi"
    assert client.create_calls[0]["title"] == "Wi-Fi"
    assert client.delete_calls == []
    assert client.assign_calls == []
    assert result["assigned"]["dry_run"] is True
    assert result["assigned"]["users"] == ["a", "z"]
    assert len(result["assigned"]["assignments"]) == 1
    assert result["assigned"]["assignments"][0]["work_item_id"] == "h-wifi"
    assert result["inserted"]["results"][0]["heading_part_id"] == "h-wifi"
    assert "delete_part_ids" not in result["inserted"]["results"][0]


def test_promote_apply_inserts_then_assigns_created_only() -> None:
    parts = [
        _heading("h-func", 1, "Functional"),
        _heading("h-a", 2, "Req A"),
        _normal("ta1", "Global ID: G-A"),
        _normal("ta2", ""),
        _normal("ta3", "Body A."),
        _heading("h-b", 2, "Req B"),
        _normal("tb1", "Global ID: G-B"),
        _normal("tb2", ""),
        _normal("tb3", "Body B."),
        _heading("h-c", 2, "Req C"),
        _normal("tc1", "Global ID: G-C"),
        _normal("tc2", ""),
        _normal("tc3", "Body C."),
    ]
    client = FakeClient(
        parts_pages=[parts],
        project_users=[ProjectUser(id="alice"), ProjectUser(id="bob")],
        create_fail_previous_parts={"h-b"},
    )
    result = promote_document_requirements(
        client,
        "ELK",
        "Doc",
        ["Functional"],
        "hardware",
        dry_run=False,
        delete_source_text=False,
    )
    assert result["planned_count"] == 3
    assert len(result["errors"]) == 1
    assert client.convert_calls == []
    assert [c["previous_part"] for c in client.create_calls] == ["h-a", "h-b", "h-c"]
    assert client.assign_calls == [
        {"work_item_id": "WI-h-a", "user_ids": ["alice"], "dry_run": False},
        {"work_item_id": "WI-h-c", "user_ids": ["bob"], "dry_run": False},
    ]
    assert client.delete_calls == []


def test_promote_apply_deletes_source_text_when_requested() -> None:
    parts = [
        _heading("h-func", 1, "Functional"),
        _heading("h-a", 2, "Req A"),
        _normal("ta1", "Global ID: G-A"),
        _normal("ta2", ""),
        _normal("ta3", "Body A."),
    ]
    client = FakeClient(parts_pages=[parts], project_users=[ProjectUser(id="alice")])
    promote_document_requirements(
        client,
        "ELK",
        "Doc",
        ["Functional"],
        "hardware",
        dry_run=False,
        assign=False,
    )
    assert client.delete_calls == [{"part_ids": ["ta1", "ta2", "ta3"], "dry_run": False}]
    assert client.create_calls[0]["previous_part"] == "h-a"


def test_parse_requirement_blocks_via_list_blocks() -> None:
    """Sanity: list_requirement_blocks wires parser to paginated parts."""
    from polarion_client.promote import list_requirement_blocks

    client = FakeClient(parts_pages=[_promotable_parts()])
    blocks = list_requirement_blocks(client, "ELK", "Doc", ["Functional"])
    assert len(blocks) == 3
    assert blocks[0].title == "Wi-Fi"
