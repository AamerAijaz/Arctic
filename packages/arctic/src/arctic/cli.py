"""arctic CLI — Polarion commands that do not use an LLM."""

from __future__ import annotations

import argparse
import json
import sys
from typing import TextIO

from polarion_client.client import PolarionClient
from polarion_client.credentials import EnvCredentialProvider
from polarion_client.errors import MissingCredentialsError, PolarionError
from polarion_client.models import CreatedWorkItem, WorkItemCreatePreview


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
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
