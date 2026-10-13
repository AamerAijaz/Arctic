"""LiveDoc requirement promotion: parse blocks, batch convert, assign, orchestrate."""

from __future__ import annotations

from typing import Any

from polarion_client.errors import PolarionError
from polarion_client.models import (
    ConvertedHeadingWorkItem,
    ConvertHeadingPreview,
    CreatedDocumentWorkItem,
    DocumentWorkItemCreatePreview,
)
from polarion_client.requirement_blocks import (
    RequirementBlock,
    is_promotable,
    parse_requirement_blocks,
)

_PAGE_SIZE = 100


def list_all_document_parts(
    client: Any,
    project_id: str,
    document_name: str,
    *,
    space_id: str = "_default",
) -> list[Any]:
    all_parts: list[Any] = []
    page_number = 1
    while True:
        page = client.list_document_parts(
            project_id,
            document_name,
            space_id=space_id,
            page_size=_PAGE_SIZE,
            page_number=page_number,
        )
        all_parts.extend(page)
        if len(page) < _PAGE_SIZE:
            break
        page_number += 1
    return all_parts


def list_requirement_blocks(
    client: Any,
    project_id: str,
    document_name: str,
    sections: list[str],
    *,
    space_id: str = "_default",
    heading_level: int = 2,
) -> list[RequirementBlock]:
    parts = list_all_document_parts(
        client, project_id, document_name, space_id=space_id
    )
    return parse_requirement_blocks(parts, sections, heading_level=heading_level)


def convert_headings_to_work_items(
    client: Any,
    project_id: str,
    document_name: str,
    items: list[dict[str, Any]],
    wi_type: str,
    *,
    space_id: str = "_default",
    dry_run: bool = True,
) -> dict[str, Any]:
    results: list[dict[str, Any]] = []
    for item in items:
        part_id = item["part_id"]
        delete_part_ids = list(item.get("delete_part_ids") or [])
        entry: dict[str, Any] = {"part_id": part_id, "ok": False}
        if dry_run and delete_part_ids:
            entry["delete_part_ids"] = delete_part_ids
        try:
            converted = client.convert_heading_to_work_item(
                project_id,
                document_name,
                part_id,
                wi_type,
                space_id=space_id,
                description=item.get("description"),
                dry_run=dry_run,
            )
        except Exception as exc:
            entry["error"] = str(exc)
            results.append(entry)
            continue

        if isinstance(converted, ConvertHeadingPreview):
            entry["work_item_id"] = converted.work_item_id
            entry["change_type_to"] = converted.change_type_to
        elif isinstance(converted, ConvertedHeadingWorkItem):
            entry["work_item_id"] = converted.id
            entry["change_type_to"] = converted.change_type_to
        else:
            entry["error"] = f"Unexpected convert result: {type(converted)!r}"
            results.append(entry)
            continue

        entry["ok"] = True
        if dry_run:
            results.append(entry)
            continue

        if delete_part_ids:
            try:
                delete_result = client.delete_document_parts(
                    project_id,
                    document_name,
                    delete_part_ids,
                    space_id=space_id,
                    dry_run=False,
                )
                entry["deleted"] = delete_result
            except Exception as exc:
                entry["delete_error"] = str(exc)
        results.append(entry)

    return {"dry_run": dry_run, "results": results}


def resolve_round_robin_users(
    client: Any,
    project_id: str,
    user_ids: list[str] | None,
) -> list[str]:
    if user_ids is not None:
        if not user_ids:
            raise PolarionError("user_ids must be non-empty when provided.")
        return list(user_ids)
    users = client.list_project_users(project_id)
    ids = sorted(user.id for user in users)
    if not ids:
        raise PolarionError(
            f"No assignable users found for project {project_id}."
        )
    return ids


def assign_work_items_round_robin(
    client: Any,
    project_id: str,
    work_item_ids: list[str],
    *,
    user_ids: list[str] | None = None,
    dry_run: bool = True,
) -> dict[str, Any]:
    users = resolve_round_robin_users(client, project_id, user_ids)
    assignments: list[dict[str, Any]] = []
    errors: list[dict[str, Any]] = []
    for i, wi in enumerate(work_item_ids):
        local = wi.split("/")[-1]
        user = users[i % len(users)]
        try:
            result = client.assign_work_item(
                project_id, local, [user], dry_run=dry_run
            )
            preview = result.to_dict() if dry_run and hasattr(result, "to_dict") else None
            assignments.append(
                {
                    "work_item_id": local,
                    "user_id": user,
                    "ok": True,
                    "preview": preview,
                }
            )
        except Exception as exc:
            errors.append(
                {"work_item_id": local, "user_id": user, "error": str(exc)}
            )
    return {
        "dry_run": dry_run,
        "assignments": assignments,
        "errors": errors,
        "users": users,
    }


def skip_reason(
    _client: Any,
    _project_id: str,
    block: RequirementBlock,
    _wi_type: str,
) -> str | None:
    if not is_promotable(block):
        return "not_promotable"
    if block.marked_work_item_id:
        return "already_marked"
    return None


def insert_requirement_work_items(
    client: Any,
    project_id: str,
    document_name: str,
    blocks: list[RequirementBlock],
    wi_type: str,
    *,
    space_id: str = "_default",
    delete_source_text: bool = True,
    dry_run: bool = True,
) -> dict[str, Any]:
    results: list[dict[str, Any]] = []
    for block in blocks:
        delete_part_ids = list(block.text_part_ids) if delete_source_text else []
        entry: dict[str, Any] = {
            "heading_part_id": block.heading_part_id,
            "title": block.title,
            "ok": False,
        }
        if dry_run and delete_part_ids:
            entry["delete_part_ids"] = delete_part_ids
        try:
            created = client.create_document_work_item(
                project_id,
                document_name,
                wi_type,
                block.title,
                space_id=space_id,
                description=block.description_html,
                previous_part=block.heading_part_id,
                dry_run=dry_run,
            )
        except Exception as exc:
            entry["error"] = str(exc)
            results.append(entry)
            continue

        if isinstance(created, DocumentWorkItemCreatePreview):
            entry["work_item_id"] = f"new/{block.heading_part_id}"
            entry["previous_part"] = created.previous_part
        elif isinstance(created, CreatedDocumentWorkItem):
            entry["work_item_id"] = created.id
            entry["part_id"] = created.part_id
        else:
            entry["error"] = f"Unexpected create result: {type(created)!r}"
            results.append(entry)
            continue

        entry["ok"] = True
        if dry_run:
            results.append(entry)
            continue

        if delete_part_ids:
            try:
                delete_result = client.delete_document_parts(
                    project_id,
                    document_name,
                    delete_part_ids,
                    space_id=space_id,
                    dry_run=False,
                )
                entry["deleted"] = delete_result
            except Exception as exc:
                entry["delete_error"] = str(exc)
        results.append(entry)

    return {"dry_run": dry_run, "results": results}


def promote_document_requirements(
    client: Any,
    project_id: str,
    document_name: str,
    sections: list[str],
    wi_type: str,
    *,
    space_id: str = "_default",
    assign: bool = True,
    delete_source_text: bool = True,
    user_ids: list[str] | None = None,
    dry_run: bool = True,
) -> dict[str, Any]:
    blocks = list_requirement_blocks(
        client,
        project_id,
        document_name,
        sections,
        space_id=space_id,
    )
    skipped: list[dict[str, Any]] = []
    planned: list[RequirementBlock] = []
    for block in blocks:
        reason = skip_reason(client, project_id, block, wi_type)
        if reason:
            skipped.append(
                {
                    "title": block.title,
                    "heading_part_id": block.heading_part_id,
                    "work_item_id": block.work_item_id,
                    "marked_work_item_id": block.marked_work_item_id,
                    "reason": reason,
                }
            )
            continue
        planned.append(block)

    insert_result: dict[str, Any] = {"dry_run": dry_run, "results": []}
    if planned:
        insert_result = insert_requirement_work_items(
            client,
            project_id,
            document_name,
            planned,
            wi_type,
            space_id=space_id,
            delete_source_text=delete_source_text,
            dry_run=dry_run,
        )

    converted_ids: list[str] = []
    for result in insert_result.get("results", []):
        if result.get("ok") and result.get("work_item_id"):
            converted_ids.append(result["work_item_id"].split("/")[-1])

    assign_result: dict[str, Any] | None = None
    if assign:
        if dry_run:
            users = resolve_round_robin_users(client, project_id, user_ids)
            assign_result = {
                "dry_run": True,
                "assignments": [
                    {
                        "work_item_id": wid,
                        "user_id": users[i % len(users)],
                        "ok": True,
                    }
                    for i, wid in enumerate(converted_ids)
                ],
                "errors": [],
                "users": users,
            }
        elif converted_ids:
            assign_result = assign_work_items_round_robin(
                client,
                project_id,
                converted_ids,
                user_ids=user_ids,
                dry_run=False,
            )

    portal_url: str | None = None
    try:
        portal_url = client.get_document(
            project_id, document_name, space_id=space_id
        ).portal_url
    except Exception:
        portal_url = None

    errors = [
        r for r in insert_result.get("results", []) if not r.get("ok")
    ]
    if assign_result:
        errors = errors + assign_result.get("errors", [])

    return {
        "dry_run": dry_run,
        "project_id": project_id,
        "document_name": document_name,
        "type": wi_type,
        "portal_url": portal_url,
        "planned_count": len(planned),
        "skipped": skipped,
        "inserted": insert_result,
        "converted": insert_result,
        "assigned": assign_result,
        "errors": errors,
    }
