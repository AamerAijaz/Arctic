"""Project and work-item tools. These never accept or return a Polarion token."""

from __future__ import annotations

from typing import Any

from fastmcp import FastMCP

from mcp_polarion.tools.auth import ClientFactory
from polarion_client.errors import PolarionError
from polarion_client.models import (
    CreatedWorkItem,
    UpdatedWorkItem,
    WorkItemCreatePreview,
    WorkItemUpdatePreview,
)


def register_workitem_tools(mcp: FastMCP, client_factory: ClientFactory) -> None:
    @mcp.tool
    def list_projects(
        query: str | None = None,
        page_size: int = 100,
        page_number: int = 1,
    ) -> dict[str, Any]:
        """List Polarion projects this user can access.

        Use this when the project id is unknown. Pass the returned `id` to
        create_work_item. Optional Lucene `query` filters the list.
        """
        try:
            projects = client_factory().list_projects(
                page_size=page_size,
                page_number=page_number,
                query=query,
            )
        except PolarionError as exc:
            raise RuntimeError(str(exc)) from None
        return {
            "projects": [project.to_dict() for project in projects],
            "page_number": page_number,
        }

    @mcp.tool
    def get_project(project_id: str) -> dict[str, Any]:
        """Get one Polarion project by id."""
        try:
            return client_factory().get_project(project_id).to_dict()
        except PolarionError as exc:
            raise RuntimeError(str(exc)) from None

    @mcp.tool
    def create_work_item(
        project_id: str,
        type: str,
        title: str,
        description: str | None = None,
        dry_run: bool = True,
    ) -> dict[str, Any]:
        """Create one work item in a Polarion project.

        `type` is the Polarion work-item type id (for example task or defect),
        not the JSON:API resource name. Call list_projects if project_id is
        unknown. Default dry_run=true only returns the request Polarion would
        receive; set dry_run=false to create the item.
        """
        try:
            result = client_factory().create_work_item(
                project_id,
                type,
                title,
                description=description,
                dry_run=dry_run,
            )
        except PolarionError as exc:
            raise RuntimeError(str(exc)) from None
        if isinstance(result, WorkItemCreatePreview):
            return result.to_dict()
        if isinstance(result, CreatedWorkItem):
            return result.to_dict()
        raise RuntimeError("Unexpected create_work_item result.")

    @mcp.tool
    def get_work_item(project_id: str, work_item_id: str) -> dict[str, str | None]:
        """Get one work item after create, by project id and work item id."""
        try:
            return client_factory().get_work_item(project_id, work_item_id).to_dict()
        except PolarionError as exc:
            raise RuntimeError(str(exc)) from None

    @mcp.tool
    def update_work_item(
        project_id: str,
        work_item_id: str,
        title: str | None = None,
        description: str | None = None,
        status: str | None = None,
        dry_run: bool = True,
    ) -> dict[str, Any]:
        """Update one work item in a Polarion project.

        Provide at least one of title, description, or status. Call
        get_work_item first if you need the current values. Default
        dry_run=true only returns the PATCH body Polarion would receive;
        set dry_run=false to apply the update.
        """
        try:
            result = client_factory().update_work_item(
                project_id,
                work_item_id,
                title=title,
                description=description,
                status=status,
                dry_run=dry_run,
            )
        except PolarionError as exc:
            raise RuntimeError(str(exc)) from None
        if isinstance(result, WorkItemUpdatePreview):
            return result.to_dict()
        if isinstance(result, UpdatedWorkItem):
            return result.to_dict()
        raise RuntimeError("Unexpected update_work_item result.")

    @mcp.tool
    def list_work_items(
        project_id: str,
        query: str | None = None,
        page_size: int = 100,
        page_number: int = 1,
    ) -> dict[str, Any]:
        """List work items in a Polarion project.

        Use Lucene `query` to find items, for example `type:requirement` or
        `type:systemrequirement`. Work-item type ids are project-specific.
        Polarion requirements are work items, not a separate resource.
        """
        try:
            items = client_factory().list_work_items(
                project_id,
                query=query,
                page_size=page_size,
                page_number=page_number,
            )
        except PolarionError as exc:
            raise RuntimeError(str(exc)) from None
        return {
            "work_items": [item.to_dict() for item in items],
            "page_number": page_number,
        }
