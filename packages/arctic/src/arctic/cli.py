"""arctic CLI — Polarion commands that do not use an LLM."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import TextIO

from polarion_client.client import PolarionClient
from polarion_client.credentials import EnvCredentialProvider
from polarion_client.errors import MissingCredentialsError, PolarionError
from polarion_client.import_apply import apply_import_preview
from polarion_client.import_files import parse_import_file
from polarion_client.models import (
    AssignedWorkItem,
    CreatedDocument,
    CreatedDocumentWorkItem,
    CreatedWorkItem,
    CreatedWorkItemLink,
    DeletedWorkItemLink,
    Document,
    DocumentCreatePreview,
    DocumentPart,
    DocumentWorkItemCreatePreview,
    ProjectUser,
    UpdatedWorkItem,
    WorkItemAssignPreview,
    WorkItemCreatePreview,
    WorkItemLink,
    WorkItemLinkDeletePreview,
    WorkItemLinkPreview,
    WorkItemUpdatePreview,
)

def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="arctic")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("whoami", help="Show the Polarion user for the configured token")

    projects = sub.add_parser("projects", help="List Polarion projects")
    projects.add_argument("--query", help="Optional Polarion Lucene query")
    projects.add_argument("--page-size", type=int, default=100)
    projects.add_argument("--page-number", type=int, default=1)

    project = sub.add_parser("project", help="Show one Polarion project")
    project.add_argument("project_id")

    create = sub.add_parser(
        "create-work-item",
        help="Create a work item (dry-run unless --apply)",
    )
    create.add_argument("--project", required=True, dest="project_id")
    create.add_argument("--type", required=True, dest="wi_type")
    create.add_argument("--title", required=True)
    create.add_argument("--description")
    create.add_argument(
        "--apply",
        action="store_true",
        help="POST to Polarion. Without this flag, print the request body only.",
    )

    work_item = sub.add_parser("work-item", help="Show one work item")
    work_item.add_argument("project_id")
    work_item.add_argument("work_item_id")

    update = sub.add_parser(
        "update-work-item",
        help="Update a work item (dry-run unless --apply)",
    )
    update.add_argument("--project", required=True, dest="project_id")
    update.add_argument("--id", required=True, dest="work_item_id")
    update.add_argument("--title")
    update.add_argument("--description")
    update.add_argument("--status")
    update.add_argument(
        "--apply",
        action="store_true",
        help="PATCH Polarion. Without this flag, print the request body only.",
    )

    work_items = sub.add_parser(
        "work-items",
        help="List work items in a project (optional Lucene query)",
    )
    work_items.add_argument("project_id")
    work_items.add_argument(
        "--query",
        help="Polarion Lucene query, for example type:requirement",
    )
    work_items.add_argument("--page-size", type=int, default=100)
    work_items.add_argument("--page-number", type=int, default=1)

    link_roles = sub.add_parser(
        "link-roles",
        help="List work-item link roles for a project",
    )
    link_roles.add_argument("project_id")

    links = sub.add_parser("links", help="List outgoing links from a work item")
    links.add_argument("project_id")
    links.add_argument("work_item_id")

    backlinks = sub.add_parser(
        "backlinks",
        help="List incoming links to a work item",
    )
    backlinks.add_argument("project_id")
    backlinks.add_argument("work_item_id")

    link = sub.add_parser(
        "link",
        help="Create a work-item link (dry-run unless --apply)",
    )
    link.add_argument("--project", required=True, dest="project_id")
    link.add_argument("--from", required=True, dest="work_item_id")
    link.add_argument("--to", required=True, dest="target_work_item_id")
    link.add_argument("--role", required=True)
    link.add_argument("--target-project", dest="target_project_id")
    link.add_argument(
        "--apply",
        action="store_true",
        help="POST to Polarion. Without this flag, print the request body only.",
    )

    unlink = sub.add_parser(
        "unlink",
        help="Delete a work-item link (dry-run unless --apply)",
    )
    unlink.add_argument("--project", required=True, dest="project_id")
    unlink.add_argument("--from", required=True, dest="work_item_id")
    unlink.add_argument("--to", required=True, dest="target_work_item_id")
    unlink.add_argument("--role", required=True)
    unlink.add_argument("--target-project", dest="target_project_id")
    unlink.add_argument(
        "--apply",
        action="store_true",
        help="DELETE on Polarion. Without this flag, print the request path only.",
    )

    documents = sub.add_parser("documents", help="List documents in a project space")
    documents.add_argument("project_id")
    documents.add_argument("--space", default="_default", dest="space_id")
    documents.add_argument("--page-size", type=int, default=100)
    documents.add_argument("--page-number", type=int, default=1)

    document = sub.add_parser("document", help="Show one document (LiveDoc)")
    document.add_argument("project_id")
    document.add_argument("document_name")
    document.add_argument("--space", default="_default", dest="space_id")

    create_document = sub.add_parser(
        "create-document",
        help="Create a document (dry-run unless --apply)",
    )
    create_document.add_argument("--project", required=True, dest="project_id")
    create_document.add_argument("--module-name", required=True)
    create_document.add_argument("--type", required=True, dest="document_type")
    create_document.add_argument("--structure-link-role", required=True)
    create_document.add_argument("--title")
    create_document.add_argument("--content", dest="home_page_content")
    create_document.add_argument("--space", default="_default", dest="space_id")
    create_document.add_argument(
        "--apply",
        action="store_true",
        help="POST to Polarion. Without this flag, print the request body only.",
    )

    document_parts = sub.add_parser(
        "document-parts",
        help="List parts of a document",
    )
    document_parts.add_argument("project_id")
    document_parts.add_argument("document_name")
    document_parts.add_argument("--space", default="_default", dest="space_id")
    document_parts.add_argument("--page-size", type=int, default=100)
    document_parts.add_argument("--page-number", type=int, default=1)

    create_doc_wi = sub.add_parser(
        "create-document-work-item",
        help="Create a work item in a document (dry-run unless --apply)",
    )
    create_doc_wi.add_argument("--project", required=True, dest="project_id")
    create_doc_wi.add_argument("--document", required=True, dest="document_name")
    create_doc_wi.add_argument("--type", required=True, dest="wi_type")
    create_doc_wi.add_argument("--title", required=True)
    create_doc_wi.add_argument("--description")
    create_doc_wi.add_argument("--space", default="_default", dest="space_id")
    create_doc_wi.add_argument(
        "--apply",
        action="store_true",
        help="POST to Polarion. Without this flag, print the request bodies only.",
    )

    import_document = sub.add_parser(
        "import-document",
        help="Import Word/ReqIF into a LiveDoc (dry-run unless --apply)",
    )
    import_document.add_argument("--project", required=True, dest="project_id")
    import_document.add_argument("--file", required=True, dest="file_path")
    import_document.add_argument("--module-name")
    import_document.add_argument("--title")
    import_document.add_argument(
        "--document-type",
        default="req_specification",
        dest="document_type",
    )
    import_document.add_argument(
        "--work-item-type",
        default="requirement",
        dest="work_item_type",
    )
    import_document.add_argument(
        "--structure-link-role",
        default="has_parent",
        dest="structure_link_role",
    )
    import_document.add_argument("--space", default="_default", dest="space_id")
    import_document.add_argument(
        "--apply",
        action="store_true",
        help="Create the LiveDoc and work items in Polarion.",
    )

    project_users = sub.add_parser(
        "project-users",
        help="List users assignable in a project",
    )
    project_users.add_argument("project_id")
    project_users.add_argument("--role", default="project_assignable")
    project_users.add_argument("--page-size", type=int, default=100)
    project_users.add_argument("--page-number", type=int, default=1)

    assign = sub.add_parser(
        "assign-work-item",
        help="Set work item assignees (dry-run unless --apply)",
    )
    assign.add_argument("--project", required=True, dest="project_id")
    assign.add_argument("--id", required=True, dest="work_item_id")
    assign.add_argument(
        "--users",
        required=True,
        help="Comma-separated Polarion user ids (replaces the full assignee list)",
    )
    assign.add_argument(
        "--apply",
        action="store_true",
        help="PATCH Polarion. Without this flag, print the request body only.",
    )
    return parser


def _handle(action, *, out: TextIO | None = None) -> int:
    stream = out or sys.stdout
    try:
        action(stream)
    except MissingCredentialsError as exc:
        print(str(exc), file=sys.stderr)
        return 2
    except PolarionError as exc:
        print(str(exc), file=sys.stderr)
        return 1
    return 0


def run_whoami(client: PolarionClient, *, out: TextIO | None = None) -> int:
    def action(stream: TextIO) -> None:
        user = client.get_current_user()
        print(f"id: {user.id}", file=stream)
        if user.name:
            print(f"name: {user.name}", file=stream)
        if user.email:
            print(f"email: {user.email}", file=stream)

    return _handle(action, out=out)


def run_projects(
    client: PolarionClient,
    *,
    query: str | None = None,
    page_size: int = 100,
    page_number: int = 1,
    out: TextIO | None = None,
) -> int:
    def action(stream: TextIO) -> None:
        projects = client.list_projects(
            query=query, page_size=page_size, page_number=page_number
        )
        if not projects:
            print("No projects.", file=stream)
            return
        for project in projects:
            name = f"  {project.name}" if project.name else ""
            print(f"{project.id}{name}", file=stream)

    return _handle(action, out=out)


def run_project(client: PolarionClient, project_id: str, *, out: TextIO | None = None) -> int:
    def action(stream: TextIO) -> None:
        project = client.get_project(project_id)
        print(f"id: {project.id}", file=stream)
        if project.name:
            print(f"name: {project.name}", file=stream)
        if project.tracker_prefix:
            print(f"tracker_prefix: {project.tracker_prefix}", file=stream)
        if project.active is not None:
            print(f"active: {project.active}", file=stream)
        if project.description:
            print(f"description: {project.description}", file=stream)

    return _handle(action, out=out)


def run_create_work_item(
    client: PolarionClient,
    *,
    project_id: str,
    wi_type: str,
    title: str,
    description: str | None,
    apply: bool,
    out: TextIO | None = None,
) -> int:
    def action(stream: TextIO) -> None:
        result = client.create_work_item(
            project_id,
            wi_type,
            title,
            description=description,
            dry_run=not apply,
        )
        if isinstance(result, WorkItemCreatePreview):
            print("dry_run: true", file=stream)
            print(json.dumps(result.body, indent=2), file=stream)
            return
        if isinstance(result, CreatedWorkItem):
            print(f"id: {result.id}", file=stream)
            if result.portal_url:
                print(f"portal_url: {result.portal_url}", file=stream)

    return _handle(action, out=out)


def run_work_item(
    client: PolarionClient,
    project_id: str,
    work_item_id: str,
    *,
    out: TextIO | None = None,
) -> int:
    def action(stream: TextIO) -> None:
        item = client.get_work_item(project_id, work_item_id)
        print(f"id: {item.id}", file=stream)
        if item.type:
            print(f"type: {item.type}", file=stream)
        if item.title:
            print(f"title: {item.title}", file=stream)
        if item.status:
            print(f"status: {item.status}", file=stream)

    return _handle(action, out=out)


def run_update_work_item(
    client: PolarionClient,
    *,
    project_id: str,
    work_item_id: str,
    title: str | None,
    description: str | None,
    status: str | None,
    apply: bool,
    out: TextIO | None = None,
) -> int:
    def action(stream: TextIO) -> None:
        result = client.update_work_item(
            project_id,
            work_item_id,
            title=title,
            description=description,
            status=status,
            dry_run=not apply,
        )
        if isinstance(result, WorkItemUpdatePreview):
            print("dry_run: true", file=stream)
            print(json.dumps(result.body, indent=2), file=stream)
            return
        if isinstance(result, UpdatedWorkItem):
            print(f"id: {result.id}", file=stream)

    return _handle(action, out=out)


def run_work_items(
    client: PolarionClient,
    project_id: str,
    *,
    query: str | None = None,
    page_size: int = 100,
    page_number: int = 1,
    out: TextIO | None = None,
) -> int:
    def action(stream: TextIO) -> None:
        items = client.list_work_items(
            project_id,
            query=query,
            page_size=page_size,
            page_number=page_number,
        )
        if not items:
            print("No work items.", file=stream)
            return
        for item in items:
            title = f"  {item.title}" if item.title else ""
            wi_type = f"  {item.type}" if item.type else ""
            print(f"{item.id}{wi_type}{title}", file=stream)

    return _handle(action, out=out)


def run_link_roles(
    client: PolarionClient, project_id: str, *, out: TextIO | None = None
) -> int:
    def action(stream: TextIO) -> None:
        roles = client.list_link_roles(project_id)
        if not roles:
            print("No link roles.", file=stream)
            return
        for role in roles:
            name = f"  {role.name}" if role.name else ""
            print(f"{role.id}{name}", file=stream)

    return _handle(action, out=out)


def _print_links(links: list[WorkItemLink], stream: TextIO) -> None:
    if not links:
        print("No links.", file=stream)
        return
    for link in links:
        role = f"  {link.role}" if link.role else ""
        target = f"  {link.target_id}" if link.target_id else ""
        title = f"  {link.target_title}" if link.target_title else ""
        print(f"{link.id}{role}{target}{title}", file=stream)


def run_links(
    client: PolarionClient,
    project_id: str,
    work_item_id: str,
    *,
    out: TextIO | None = None,
) -> int:
    def action(stream: TextIO) -> None:
        _print_links(
            client.list_work_item_links(project_id, work_item_id), stream
        )

    return _handle(action, out=out)


def run_backlinks(
    client: PolarionClient,
    project_id: str,
    work_item_id: str,
    *,
    out: TextIO | None = None,
) -> int:
    def action(stream: TextIO) -> None:
        _print_links(
            client.list_work_item_backlinks(project_id, work_item_id), stream
        )

    return _handle(action, out=out)


def run_link(
    client: PolarionClient,
    *,
    project_id: str,
    work_item_id: str,
    target_work_item_id: str,
    role: str,
    target_project_id: str | None,
    apply: bool,
    out: TextIO | None = None,
) -> int:
    def action(stream: TextIO) -> None:
        result = client.create_work_item_link(
            project_id,
            work_item_id,
            target_work_item_id,
            role,
            target_project_id=target_project_id,
            dry_run=not apply,
        )
        if isinstance(result, WorkItemLinkPreview):
            print("dry_run: true", file=stream)
            print(json.dumps(result.body, indent=2), file=stream)
            return
        if isinstance(result, CreatedWorkItemLink):
            print(f"id: {result.id}", file=stream)

    return _handle(action, out=out)


def run_unlink(
    client: PolarionClient,
    *,
    project_id: str,
    work_item_id: str,
    target_work_item_id: str,
    role: str,
    target_project_id: str | None,
    apply: bool,
    out: TextIO | None = None,
) -> int:
    def action(stream: TextIO) -> None:
        result = client.delete_work_item_link(
            project_id,
            work_item_id,
            target_work_item_id,
            role,
            target_project_id=target_project_id,
            dry_run=not apply,
        )
        if isinstance(result, WorkItemLinkDeletePreview):
            print("dry_run: true", file=stream)
            print(f"path: {result.path}", file=stream)
            return
        if isinstance(result, DeletedWorkItemLink):
            print(f"id: {result.id}", file=stream)

    return _handle(action, out=out)


def _print_document(doc: Document, stream: TextIO) -> None:
    print(f"id: {doc.id}", file=stream)
    if doc.module_name:
        print(f"module_name: {doc.module_name}", file=stream)
    if doc.title:
        print(f"title: {doc.title}", file=stream)
    if doc.type:
        print(f"type: {doc.type}", file=stream)
    if doc.status:
        print(f"status: {doc.status}", file=stream)
    if doc.space_id:
        print(f"space_id: {doc.space_id}", file=stream)
    if doc.portal_url:
        print(f"portal_url: {doc.portal_url}", file=stream)
    if doc.content_summary:
        print(f"content_summary: {doc.content_summary}", file=stream)


def _print_document_parts(parts: list[DocumentPart], stream: TextIO) -> None:
    if not parts:
        print("No document parts.", file=stream)
        return
    for part in parts:
        heading = f"  {part.heading_text}" if part.heading_text else ""
        ptype = f"  {part.part_type}" if part.part_type else ""
        print(f"{part.id}{ptype}{heading}", file=stream)


def _print_project_users(users: list[ProjectUser], stream: TextIO) -> None:
    if not users:
        print("No users.", file=stream)
        return
    for user in users:
        name = f"  {user.name}" if user.name else ""
        email = f"  {user.email}" if user.email else ""
        print(f"{user.id}{name}{email}", file=stream)


def _print_import_apply_result(result: dict, stream: TextIO) -> None:
    for key, value in result.items():
        if value is None:
            continue
        if isinstance(value, list):
            for item in value:
                print(f"{key}: {item}", file=stream)
        else:
            print(f"{key}: {value}", file=stream)


def run_documents(
    client: PolarionClient,
    project_id: str,
    *,
    space_id: str = "_default",
    page_size: int = 100,
    page_number: int = 1,
    out: TextIO | None = None,
) -> int:
    def action(stream: TextIO) -> None:
        docs = client.list_documents(
            project_id,
            space_id=space_id,
            page_size=page_size,
            page_number=page_number,
        )
        if not docs:
            print("No documents.", file=stream)
            return
        for doc in docs:
            name = f"  {doc.title}" if doc.title else ""
            module = f"  {doc.module_name}" if doc.module_name else ""
            print(f"{doc.id}{module}{name}", file=stream)

    return _handle(action, out=out)


def run_document(
    client: PolarionClient,
    project_id: str,
    document_name: str,
    *,
    space_id: str = "_default",
    out: TextIO | None = None,
) -> int:
    def action(stream: TextIO) -> None:
        _print_document(
            client.get_document(project_id, document_name, space_id=space_id),
            stream,
        )

    return _handle(action, out=out)


def run_create_document(
    client: PolarionClient,
    *,
    project_id: str,
    module_name: str,
    document_type: str,
    structure_link_role: str,
    title: str | None,
    home_page_content: str | None,
    space_id: str,
    apply: bool,
    out: TextIO | None = None,
) -> int:
    def action(stream: TextIO) -> None:
        result = client.create_document(
            project_id,
            module_name,
            title=title,
            document_type=document_type,
            structure_link_role=structure_link_role,
            home_page_content=home_page_content,
            space_id=space_id,
            dry_run=not apply,
        )
        if isinstance(result, DocumentCreatePreview):
            print("dry_run: true", file=stream)
            print(json.dumps(result.body, indent=2), file=stream)
            return
        if isinstance(result, CreatedDocument):
            print(f"id: {result.id}", file=stream)
            if result.portal_url:
                print(f"portal_url: {result.portal_url}", file=stream)

    return _handle(action, out=out)


def run_document_parts(
    client: PolarionClient,
    project_id: str,
    document_name: str,
    *,
    space_id: str = "_default",
    page_size: int = 100,
    page_number: int = 1,
    out: TextIO | None = None,
) -> int:
    def action(stream: TextIO) -> None:
        _print_document_parts(
            client.list_document_parts(
                project_id,
                document_name,
                space_id=space_id,
                page_size=page_size,
                page_number=page_number,
            ),
            stream,
        )

    return _handle(action, out=out)


def run_create_document_work_item(
    client: PolarionClient,
    *,
    project_id: str,
    document_name: str,
    wi_type: str,
    title: str,
    description: str | None,
    space_id: str,
    apply: bool,
    out: TextIO | None = None,
) -> int:
    def action(stream: TextIO) -> None:
        result = client.create_document_work_item(
            project_id,
            document_name,
            wi_type,
            title,
            space_id=space_id,
            description=description,
            dry_run=not apply,
        )
        if isinstance(result, DocumentWorkItemCreatePreview):
            print("dry_run: true", file=stream)
            print(json.dumps(result.to_dict(), indent=2), file=stream)
            return
        if isinstance(result, CreatedDocumentWorkItem):
            print(f"id: {result.id}", file=stream)
            print(f"part_id: {result.part_id}", file=stream)
            if result.portal_url:
                print(f"portal_url: {result.portal_url}", file=stream)

    return _handle(action, out=out)


def run_import_document(
    client: PolarionClient,
    *,
    project_id: str,
    file_path: str,
    module_name: str | None,
    title: str | None,
    document_type: str,
    work_item_type: str,
    structure_link_role: str,
    space_id: str,
    apply: bool,
    out: TextIO | None = None,
) -> int:
    def action(stream: TextIO) -> None:
        preview = parse_import_file(
            file_path,
            module_name=module_name,
            title=title,
            work_item_type=work_item_type,
            structure_link_role=structure_link_role,
        )
        plan = preview.documents[0] if preview.documents else None
        resolved_name = module_name or (plan.module_name if plan else None) or Path(file_path).stem
        resolved_title = title or (plan.title if plan else None) or resolved_name
        if Path(file_path).suffix.lower() == ".docx":
            if not apply:
                print("dry_run: true", file=stream)
                print("mode: polarion_word_import", file=stream)
                print(json.dumps({
                    "parameters": {
                        "documentName": resolved_name,
                        "documentType": document_type,
                        "title": resolved_title,
                    },
                    "lossy_local_parse": preview.to_dict(),
                }, indent=2), file=stream)
                return
            result = client.import_word_document(
                project_id,
                file_path,
                module_name=resolved_name,
                document_type=document_type,
                title=resolved_title,
                space_id=space_id,
                dry_run=False,
            )
            _print_import_apply_result(result, stream)
            return
        if not apply:
            print("dry_run: true", file=stream)
            print(json.dumps(preview.to_dict(), indent=2), file=stream)
            return
        result = apply_import_preview(
            client,
            project_id,
            preview,
            source_path=file_path,
            document_type=document_type,
            structure_link_role=structure_link_role,
            space_id=space_id,
        )
        _print_import_apply_result(result, stream)

    return _handle(action, out=out)


def run_project_users(
    client: PolarionClient,
    project_id: str,
    *,
    role: str = "project_assignable",
    page_size: int = 100,
    page_number: int = 1,
    out: TextIO | None = None,
) -> int:
    def action(stream: TextIO) -> None:
        _print_project_users(
            client.list_project_users(
                project_id,
                role=role,
                page_size=page_size,
                page_number=page_number,
            ),
            stream,
        )

    return _handle(action, out=out)


def run_assign_work_item(
    client: PolarionClient,
    *,
    project_id: str,
    work_item_id: str,
    user_ids: list[str],
    apply: bool,
    out: TextIO | None = None,
) -> int:
    def action(stream: TextIO) -> None:
        result = client.assign_work_item(
            project_id,
            work_item_id,
            user_ids,
            dry_run=not apply,
        )
        if isinstance(result, WorkItemAssignPreview):
            print("dry_run: true", file=stream)
            print(json.dumps(result.body, indent=2), file=stream)
            return
        if isinstance(result, AssignedWorkItem):
            print(f"id: {result.id}", file=stream)

    return _handle(action, out=out)


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    client = PolarionClient(EnvCredentialProvider())
    if args.command == "whoami":
        return run_whoami(client)
    if args.command == "projects":
        return run_projects(
            client,
            query=args.query,
            page_size=args.page_size,
            page_number=args.page_number,
        )
    if args.command == "project":
        return run_project(client, args.project_id)
    if args.command == "create-work-item":
        return run_create_work_item(
            client,
            project_id=args.project_id,
            wi_type=args.wi_type,
            title=args.title,
            description=args.description,
            apply=args.apply,
        )
    if args.command == "work-item":
        return run_work_item(client, args.project_id, args.work_item_id)
    if args.command == "update-work-item":
        return run_update_work_item(
            client,
            project_id=args.project_id,
            work_item_id=args.work_item_id,
            title=args.title,
            description=args.description,
            status=args.status,
            apply=args.apply,
        )
    if args.command == "work-items":
        return run_work_items(
            client,
            args.project_id,
            query=args.query,
            page_size=args.page_size,
            page_number=args.page_number,
        )
    if args.command == "link-roles":
        return run_link_roles(client, args.project_id)
    if args.command == "links":
        return run_links(client, args.project_id, args.work_item_id)
    if args.command == "backlinks":
        return run_backlinks(client, args.project_id, args.work_item_id)
    if args.command == "link":
        return run_link(
            client,
            project_id=args.project_id,
            work_item_id=args.work_item_id,
            target_work_item_id=args.target_work_item_id,
            role=args.role,
            target_project_id=args.target_project_id,
            apply=args.apply,
        )
    if args.command == "unlink":
        return run_unlink(
            client,
            project_id=args.project_id,
            work_item_id=args.work_item_id,
            target_work_item_id=args.target_work_item_id,
            role=args.role,
            target_project_id=args.target_project_id,
            apply=args.apply,
        )
    if args.command == "documents":
        return run_documents(
            client,
            args.project_id,
            space_id=args.space_id,
            page_size=args.page_size,
            page_number=args.page_number,
        )
    if args.command == "document":
        return run_document(
            client,
            args.project_id,
            args.document_name,
            space_id=args.space_id,
        )
    if args.command == "create-document":
        return run_create_document(
            client,
            project_id=args.project_id,
            module_name=args.module_name,
            document_type=args.document_type,
            structure_link_role=args.structure_link_role,
            title=args.title,
            home_page_content=args.home_page_content,
            space_id=args.space_id,
            apply=args.apply,
        )
    if args.command == "document-parts":
        return run_document_parts(
            client,
            args.project_id,
            args.document_name,
            space_id=args.space_id,
            page_size=args.page_size,
            page_number=args.page_number,
        )
    if args.command == "create-document-work-item":
        return run_create_document_work_item(
            client,
            project_id=args.project_id,
            document_name=args.document_name,
            wi_type=args.wi_type,
            title=args.title,
            description=args.description,
            space_id=args.space_id,
            apply=args.apply,
        )
    if args.command == "import-document":
        return run_import_document(
            client,
            project_id=args.project_id,
            file_path=args.file_path,
            module_name=args.module_name,
            title=args.title,
            document_type=args.document_type,
            work_item_type=args.work_item_type,
            structure_link_role=args.structure_link_role,
            space_id=args.space_id,
            apply=args.apply,
        )
    if args.command == "project-users":
        return run_project_users(
            client,
            args.project_id,
            role=args.role,
            page_size=args.page_size,
            page_number=args.page_number,
        )
    if args.command == "assign-work-item":
        user_ids = [u.strip() for u in args.users.split(",") if u.strip()]
        return run_assign_work_item(
            client,
            project_id=args.project_id,
            work_item_id=args.work_item_id,
            user_ids=user_ids,
            apply=args.apply,
        )
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
