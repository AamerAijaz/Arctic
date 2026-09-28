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
