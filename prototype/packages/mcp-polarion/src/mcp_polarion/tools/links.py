"""Work-item link tools. These never accept or return a Polarion token."""

from __future__ import annotations

from typing import Any

from fastmcp import FastMCP

from mcp_polarion.tools.auth import ClientFactory
from polarion_client.errors import PolarionError
from polarion_client.models import (
    CreatedWorkItemLink,
    DeletedWorkItemLink,
    WorkItemLinkDeletePreview,
    WorkItemLinkPreview,
)


def register_link_tools(mcp: FastMCP, client_factory: ClientFactory) -> None:
    @mcp.tool
    def list_link_roles(project_id: str) -> dict[str, Any]:
        """List Polarion work-item link roles for a project.

        Call this before create_work_item_link. Polarion rejects roles that
        violate the project's linkRules (fromTypes / toTypes). Role ids are
        project-specific (for example implements or relates_to).
        """
        try:
            roles = client_factory().list_link_roles(project_id)
        except PolarionError as exc:
            raise RuntimeError(str(exc)) from None
        return {"roles": [role.to_dict() for role in roles]}

    @mcp.tool
    def list_work_item_links(
        project_id: str, work_item_id: str
    ) -> dict[str, Any]:
        """List outgoing links from a work item to other work items."""
        try:
            links = client_factory().list_work_item_links(project_id, work_item_id)
        except PolarionError as exc:
            raise RuntimeError(str(exc)) from None
        return {"links": [link.to_dict() for link in links]}

    @mcp.tool
    def list_work_item_backlinks(
        project_id: str, work_item_id: str
    ) -> dict[str, Any]:
        """List incoming links to a work item (what points at this item).

        Use this on a requirement to see which work items implement it.
        """
        try:
            links = client_factory().list_work_item_backlinks(
                project_id, work_item_id
            )
        except PolarionError as exc:
            raise RuntimeError(str(exc)) from None
        return {"links": [link.to_dict() for link in links]}

    @mcp.tool
    def create_work_item_link(
        project_id: str,
        work_item_id: str,
        target_work_item_id: str,
        role: str,
        target_project_id: str | None = None,
        dry_run: bool = True,
    ) -> dict[str, Any]:
        """Create an outgoing link from a work item to another work item.

        `role` is a Polarion link-role id from list_link_roles, not a display
        name. `target_project_id` defaults to `project_id`. Default
        dry_run=true only returns the POST body Polarion would receive; set
        dry_run=false to create the link.
        """
        try:
            result = client_factory().create_work_item_link(
                project_id,
                work_item_id,
                target_work_item_id,
                role,
                target_project_id=target_project_id,
                dry_run=dry_run,
            )
        except PolarionError as exc:
            raise RuntimeError(str(exc)) from None
        if isinstance(result, WorkItemLinkPreview):
            return result.to_dict()
        if isinstance(result, CreatedWorkItemLink):
            return result.to_dict()
        raise RuntimeError("Unexpected create_work_item_link result.")

    @mcp.tool
    def delete_work_item_link(
        project_id: str,
        work_item_id: str,
        target_work_item_id: str,
        role: str,
        target_project_id: str | None = None,
        dry_run: bool = True,
    ) -> dict[str, Any]:
        """Delete one outgoing link from a work item.

        Default dry_run=true only returns the DELETE path Polarion would
        receive; set dry_run=false to remove the link.
        """
        try:
            result = client_factory().delete_work_item_link(
                project_id,
                work_item_id,
                target_work_item_id,
                role,
                target_project_id=target_project_id,
                dry_run=dry_run,
            )
        except PolarionError as exc:
            raise RuntimeError(str(exc)) from None
        if isinstance(result, WorkItemLinkDeletePreview):
            return result.to_dict()
        if isinstance(result, DeletedWorkItemLink):
            return result.to_dict()
        raise RuntimeError("Unexpected delete_work_item_link result.")
