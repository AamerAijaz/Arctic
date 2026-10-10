"""Polarion REST resources used by Arctic."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any


@dataclass(frozen=True)
class CurrentUser:
    id: str
    name: str | None = None
    email: str | None = None

    def to_dict(self) -> dict[str, str | None]:
        return {"id": self.id, "name": self.name, "email": self.email}


@dataclass(frozen=True)
class Project:
    id: str
    name: str | None = None
    description: str | None = None
    active: bool | None = None
    tracker_prefix: str | None = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "name": self.name,
            "description": self.description,
            "active": self.active,
            "tracker_prefix": self.tracker_prefix,
        }


@dataclass(frozen=True)
class WorkItem:
    id: str
    title: str | None = None
    type: str | None = None
    status: str | None = None
    description: str | None = None
    assignee_ids: tuple[str, ...] = ()

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "title": self.title,
            "type": self.type,
            "status": self.status,
            "description": self.description,
            "assignee_ids": list(self.assignee_ids),
        }


@dataclass(frozen=True)
class CreatedWorkItem:
    id: str
    portal_url: str | None = None

    def to_dict(self) -> dict[str, str | None]:
        return {"id": self.id, "portal_url": self.portal_url}


@dataclass(frozen=True)
class WorkItemCreatePreview:
    project_id: str
    body: dict[str, Any]

    def to_dict(self) -> dict[str, Any]:
        return {
            "dry_run": True,
            "project_id": self.project_id,
            "body": self.body,
        }


@dataclass(frozen=True)
class UpdatedWorkItem:
    id: str

    def to_dict(self) -> dict[str, str]:
        return {"id": self.id}


@dataclass(frozen=True)
class WorkItemUpdatePreview:
    project_id: str
    work_item_id: str
    body: dict[str, Any]
    change_type_to: str | None = None

    def to_dict(self) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "dry_run": True,
            "project_id": self.project_id,
            "work_item_id": self.work_item_id,
            "body": self.body,
        }
        if self.change_type_to is not None:
            payload["change_type_to"] = self.change_type_to
        return payload


@dataclass(frozen=True)
class LinkRole:
    id: str
    name: str | None = None
    opposite_name: str | None = None
    link_rules: list[dict[str, Any]] | None = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "name": self.name,
            "opposite_name": self.opposite_name,
            "link_rules": self.link_rules,
        }


@dataclass(frozen=True)
class WorkItemLink:
    id: str
    role: str | None = None
    target_id: str | None = None
    target_title: str | None = None

    def to_dict(self) -> dict[str, str | None]:
        return {
            "id": self.id,
            "role": self.role,
            "target_id": self.target_id,
            "target_title": self.target_title,
        }


@dataclass(frozen=True)
class CreatedWorkItemLink:
    id: str

    def to_dict(self) -> dict[str, str]:
        return {"id": self.id}


@dataclass(frozen=True)
class WorkItemLinkPreview:
    project_id: str
    work_item_id: str
    body: dict[str, Any]

    def to_dict(self) -> dict[str, Any]:
        return {
            "dry_run": True,
            "project_id": self.project_id,
            "work_item_id": self.work_item_id,
            "body": self.body,
        }


@dataclass(frozen=True)
class DeletedWorkItemLink:
    id: str

    def to_dict(self) -> dict[str, str]:
        return {"id": self.id}


@dataclass(frozen=True)
class WorkItemLinkDeletePreview:
    project_id: str
    work_item_id: str
    role: str
    target_project_id: str
    target_work_item_id: str
    path: str

    def to_dict(self) -> dict[str, Any]:
        return {
            "dry_run": True,
            "project_id": self.project_id,
            "work_item_id": self.work_item_id,
            "role": self.role,
            "target_project_id": self.target_project_id,
            "target_work_item_id": self.target_work_item_id,
            "path": self.path,
        }


@dataclass(frozen=True)
class Document:
    id: str
    module_name: str | None = None
    title: str | None = None
    type: str | None = None
    status: str | None = None
    space_id: str | None = None
    portal_url: str | None = None
    content_summary: str | None = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "module_name": self.module_name,
            "title": self.title,
            "type": self.type,
            "status": self.status,
            "space_id": self.space_id,
            "portal_url": self.portal_url,
            "content_summary": self.content_summary,
        }


@dataclass(frozen=True)
class DocumentPart:
    id: str
    part_type: str | None = None
    level: int | None = None
    heading_text: str | None = None
    text: str | None = None
    work_item_id: str | None = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "part_type": self.part_type,
            "level": self.level,
            "heading_text": self.heading_text,
            "text": self.text,
            "work_item_id": self.work_item_id,
        }


@dataclass(frozen=True)
class CreatedDocument:
    id: str
    portal_url: str | None = None

    def to_dict(self) -> dict[str, str | None]:
        return {"id": self.id, "portal_url": self.portal_url}


@dataclass(frozen=True)
class DocumentCreatePreview:
    project_id: str
    space_id: str
    body: dict[str, Any]

    def to_dict(self) -> dict[str, Any]:
        return {
            "dry_run": True,
            "project_id": self.project_id,
            "space_id": self.space_id,
            "body": self.body,
        }


@dataclass(frozen=True)
class CreatedDocumentWorkItem:
    id: str
    part_id: str
    portal_url: str | None = None

    def to_dict(self) -> dict[str, str | None]:
        return {
            "id": self.id,
            "part_id": self.part_id,
            "portal_url": self.portal_url,
        }


@dataclass(frozen=True)
class ConvertedHeadingWorkItem:
    id: str
    part_id: str
    change_type_to: str

    def to_dict(self) -> dict[str, str]:
        return {
            "id": self.id,
            "part_id": self.part_id,
            "change_type_to": self.change_type_to,
        }


@dataclass(frozen=True)
class ConvertHeadingPreview:
    project_id: str
    document_name: str
    part_id: str
    work_item_id: str
    change_type_to: str
    body: dict[str, Any]

    def to_dict(self) -> dict[str, Any]:
        return {
            "dry_run": True,
            "project_id": self.project_id,
            "document_name": self.document_name,
            "part_id": self.part_id,
            "work_item_id": self.work_item_id,
            "change_type_to": self.change_type_to,
            "body": self.body,
        }


@dataclass(frozen=True)
class DocumentWorkItemCreatePreview:
    project_id: str
    work_item_body: dict[str, Any]
    part_body: dict[str, Any]
    after: str | None = None
    before: str | None = None
    parent: str | None = None
    previous_part: str | None = None
    next_part: str | None = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "dry_run": True,
            "project_id": self.project_id,
            "work_item_body": self.work_item_body,
            "part_body": self.part_body,
            "after": self.after,
            "before": self.before,
            "parent": self.parent,
            "previous_part": self.previous_part,
            "next_part": self.next_part,
        }


@dataclass(frozen=True)
class ProjectUser:
    id: str
    name: str | None = None
    email: str | None = None

    def to_dict(self) -> dict[str, str | None]:
        return {"id": self.id, "name": self.name, "email": self.email}


@dataclass(frozen=True)
class WorkItemAssignPreview:
    project_id: str
    work_item_id: str
    body: dict[str, Any]

    def to_dict(self) -> dict[str, Any]:
        return {
            "dry_run": True,
            "project_id": self.project_id,
            "work_item_id": self.work_item_id,
            "body": self.body,
        }


@dataclass(frozen=True)
class AssignedWorkItem:
    id: str

    def to_dict(self) -> dict[str, str]:
        return {"id": self.id}


@dataclass(frozen=True)
class PolarionJob:
    id: str
    state: str | None = None
    status_type: str | None = None
    message: str | None = None

    def to_dict(self) -> dict[str, str | None]:
        return {
            "id": self.id,
            "state": self.state,
            "status_type": self.status_type,
            "message": self.message,
        }

    @property
    def is_terminal(self) -> bool:
        status = (self.status_type or "").upper()
        state = (self.state or "").upper()
        if status in {"OK", "FAILED", "CANCELLED"}:
            return True
        return state in {"FINISHED", "FAILED", "CANCELLED", "DONE"}

    @property
    def failed(self) -> bool:
        status = (self.status_type or "").upper()
        state = (self.state or "").upper()
        return status in {"FAILED", "CANCELLED"} or state in {"FAILED", "CANCELLED"}
