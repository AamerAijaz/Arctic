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

    def to_dict(self) -> dict[str, str | None]:
        return {
            "id": self.id,
            "title": self.title,
            "type": self.type,
            "status": self.status,
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

    def to_dict(self) -> dict[str, Any]:
        return {
            "dry_run": True,
            "project_id": self.project_id,
            "work_item_id": self.work_item_id,
            "body": self.body,
        }


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
