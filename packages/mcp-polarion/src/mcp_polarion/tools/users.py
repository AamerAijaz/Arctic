"""Project user and assignee tools. These never accept or return a Polarion token."""

from __future__ import annotations

from typing import Any

from fastmcp import FastMCP

from mcp_polarion.tools.auth import ClientFactory
from polarion_client.errors import PolarionError
from polarion_client.models import AssignedWorkItem, WorkItemAssignPreview


def register_user_tools(mcp: FastMCP, client_factory: ClientFactory) -> None:
    @mcp.tool
    def list_project_users(
        project_id: str,
        role: str = "project_assignable",
        page_size: int = 100,
        page_number: int = 1,
    ) -> dict[str, Any]:
        """List Polarion users for a project, filtered by project role.

        Call this before assign_work_item to pick valid user ids (for example
        users with the project_assignable role).
        """
        try:
            users = client_factory().list_project_users(
                project_id,
                role=role,
                page_size=page_size,
                page_number=page_number,
            )
        except PolarionError as exc:
            raise RuntimeError(str(exc)) from None
        return {
            "users": [user.to_dict() for user in users],
            "page_number": page_number,
        }

    @mcp.tool
    def assign_work_item(
        project_id: str,
        work_item_id: str,
        user_ids: list[str],
        dry_run: bool = True,
    ) -> dict[str, Any]:
        """Assign Polarion users to a work item.

        Call list_project_users first to choose assignable user ids. Default
        dry_run=true only returns the PATCH body; set dry_run=false to apply.
        Pass an empty user_ids list to clear assignees.
        """
        try:
            result = client_factory().assign_work_item(
                project_id,
                work_item_id,
                user_ids,
                dry_run=dry_run,
            )
        except PolarionError as exc:
            raise RuntimeError(str(exc)) from None
        if isinstance(result, WorkItemAssignPreview):
            return result.to_dict()
        if isinstance(result, AssignedWorkItem):
            return result.to_dict()
        raise RuntimeError("Unexpected assign_work_item result.")

    @mcp.tool
    def assign_work_items_round_robin(
        project_id: str,
        work_item_ids: list[str],
        user_ids: list[str] | None = None,
        dry_run: bool = True,
    ) -> dict[str, Any]:
        """Assign work items to project users in round-robin order.

        `work_item_ids` are local ids (for example ELK-42). When `user_ids`
        is omitted, uses list_project_users sorted by id. Fails when no users
        are available. Default dry_run=true; set dry_run=false to apply.
        """
        try:
            return client_factory().assign_work_items_round_robin(
                project_id,
                work_item_ids,
                user_ids=user_ids,
                dry_run=dry_run,
            )
        except PolarionError as exc:
            raise RuntimeError(str(exc)) from None
