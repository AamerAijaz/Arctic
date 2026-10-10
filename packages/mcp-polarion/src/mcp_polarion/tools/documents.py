"""Polarion LiveDoc / document tools. These never accept or return a Polarion token."""

from __future__ import annotations

from pathlib import Path
from typing import Any

from fastmcp import FastMCP

from mcp_polarion.tools.auth import ClientFactory
from polarion_client.errors import PolarionError
from polarion_client.import_apply import apply_import_preview
from polarion_client.import_files import parse_import_file
from polarion_client.models import (
    ConvertedHeadingWorkItem,
    ConvertHeadingPreview,
    CreatedDocument,
    CreatedDocumentWorkItem,
    DocumentCreatePreview,
    DocumentWorkItemCreatePreview,
)


def register_document_tools(mcp: FastMCP, client_factory: ClientFactory) -> None:
    @mcp.tool
    def list_documents(
        project_id: str,
        space_id: str = "_default",
        page_size: int = 100,
        page_number: int = 1,
    ) -> dict[str, Any]:
        """List Polarion documents (LiveDocs) in a project space.

        Returned `portal_url` opens the LiveDoc in Polarion. After import or
        edits, call list_document_parts to see headings and embedded work items.
        """
        try:
            documents = client_factory().list_documents(
                project_id,
                space_id=space_id,
                page_size=page_size,
                page_number=page_number,
            )
        except PolarionError as exc:
            raise RuntimeError(str(exc)) from None
        return {
            "documents": [doc.to_dict() for doc in documents],
            "page_number": page_number,
        }

    @mcp.tool
    def get_document(
        project_id: str,
        document_name: str,
        space_id: str = "_default",
    ) -> dict[str, Any]:
        """Get one Polarion document including a short home-page text summary.

        `portal_url` is the LiveDoc preview link in Polarion.
        """
        try:
            return client_factory().get_document(
                project_id, document_name, space_id=space_id
            ).to_dict()
        except PolarionError as exc:
            raise RuntimeError(str(exc)) from None

    @mcp.tool
    def create_document(
        project_id: str,
        module_name: str,
        document_type: str,
        structure_link_role: str,
        title: str | None = None,
        home_page_content: str | None = None,
        space_id: str = "_default",
        dry_run: bool = True,
    ) -> dict[str, Any]:
        """Create a Polarion LiveDoc in a space.

        Call list_link_roles first to pick a valid `structure_link_role` for
        document hierarchy. Default dry_run=true only returns the POST body;
        set dry_run=false to create the document. `portal_url` in the result
        opens the LiveDoc preview.
        """
        try:
            result = client_factory().create_document(
                project_id,
                module_name,
                title=title,
                document_type=document_type,
                structure_link_role=structure_link_role,
                home_page_content=home_page_content,
                space_id=space_id,
                dry_run=dry_run,
            )
        except PolarionError as exc:
            raise RuntimeError(str(exc)) from None
        if isinstance(result, DocumentCreatePreview):
            return result.to_dict()
        if isinstance(result, CreatedDocument):
            return result.to_dict()
        raise RuntimeError("Unexpected create_document result.")

    @mcp.tool
    def list_document_parts(
        project_id: str,
        document_name: str,
        space_id: str = "_default",
        page_size: int = 100,
        page_number: int = 1,
    ) -> dict[str, Any]:
        """List parts of a LiveDoc (headings, text blocks, work-item embeds).

        Heading and work-item parts include `work_item_id` when Polarion
        linked that part to a work item. Use that id with
        convert_heading_to_work_item. Call this after the user edits a
        document in Polarion to refresh structure and content.
        """
        try:
            parts = client_factory().list_document_parts(
                project_id,
                document_name,
                space_id=space_id,
                page_size=page_size,
                page_number=page_number,
            )
        except PolarionError as exc:
            raise RuntimeError(str(exc)) from None
        return {
            "parts": [part.to_dict() for part in parts],
            "page_number": page_number,
        }

    @mcp.tool
    def move_document_part(
        project_id: str,
        document_name: str,
        part_id: str,
        after: str | None = None,
        before: str | None = None,
        parent: str | None = None,
        space_id: str = "_default",
        dry_run: bool = True,
    ) -> dict[str, Any]:
        """Move a LiveDoc part so it sits after or before another part.

        Use list_document_parts first. `parent` nests a work-item part under a
        heading or another work item. `after`/`before` must be siblings of that parent.
        """
        try:
            return client_factory().move_document_part(
                project_id,
                document_name,
                part_id,
                space_id=space_id,
                after=after,
                before=before,
                parent=parent,
                dry_run=dry_run,
            )
        except PolarionError as exc:
            raise RuntimeError(str(exc)) from None

    @mcp.tool
    def delete_document_parts(
        project_id: str,
        document_name: str,
        part_ids: list[str],
        space_id: str = "_default",
        dry_run: bool = True,
    ) -> dict[str, Any]:
        """Delete LiveDoc parts (typically leftover plain-text paragraphs)."""
        try:
            return client_factory().delete_document_parts(
                project_id,
                document_name,
                part_ids,
                space_id=space_id,
                dry_run=dry_run,
            )
        except PolarionError as exc:
            raise RuntimeError(str(exc)) from None

    @mcp.tool
    def convert_heading_to_work_item(
        project_id: str,
        document_name: str,
        part_id: str,
        type: str,
        description: str | None = None,
        space_id: str = "_default",
        dry_run: bool = True,
    ) -> dict[str, Any]:
        """Convert a LiveDoc heading work item to another work-item type in place.

        This is Polarion's heading-to-work-item change (PATCH changeTypeTo),
        not create_document_work_item. Call list_document_parts first and pass
        a heading `part_id`. `type` is the target Polarion work-item type id
        (for example requirement or hardware). Optional `description` is HTML
        set on the same PATCH. Default dry_run=true only returns the PATCH
        Polarion would receive; set dry_run=false to apply.
        """
        try:
            result = client_factory().convert_heading_to_work_item(
                project_id,
                document_name,
                part_id,
                type,
                space_id=space_id,
                description=description,
                dry_run=dry_run,
            )
        except PolarionError as exc:
            raise RuntimeError(str(exc)) from None
        if isinstance(result, ConvertHeadingPreview):
            return result.to_dict()
        if isinstance(result, ConvertedHeadingWorkItem):
            return result.to_dict()
        raise RuntimeError("Unexpected convert_heading_to_work_item result.")

    @mcp.tool
    def create_document_work_item(
        project_id: str,
        document_name: str,
        type: str,
        title: str,
        space_id: str = "_default",
        description: str | None = None,
        after: str | None = None,
        before: str | None = None,
        parent: str | None = None,
        previous_part: str | None = None,
        next_part: str | None = None,
        dry_run: bool = True,
    ) -> dict[str, Any]:
        """Create a work item and embed it in a LiveDoc as a document part.

        `type` is the Polarion work-item type id. Creating with the document
        module puts the WI in the Recycle Bin. Pass `previous_part` or
        `next_part` (a part id from list_document_parts) to insert it in the
        body at that position. Without those, Polarion appends at the end;
        `parent`/`after`/`before` then move the appended part. Default
        dry_run=true only returns the work-item and part bodies Polarion
        would receive.
        """
        try:
            result = client_factory().create_document_work_item(
                project_id,
                document_name,
                type,
                title,
                space_id=space_id,
                description=description,
                after=after,
                before=before,
                parent=parent,
                previous_part=previous_part,
                next_part=next_part,
                dry_run=dry_run,
            )
        except PolarionError as exc:
            raise RuntimeError(str(exc)) from None
        if isinstance(result, DocumentWorkItemCreatePreview):
            return result.to_dict()
        if isinstance(result, CreatedDocumentWorkItem):
            return result.to_dict()
        raise RuntimeError("Unexpected create_document_work_item result.")

    @mcp.tool
    def import_document(
        project_id: str,
        file_path: str,
        module_name: str | None = None,
        title: str | None = None,
        document_type: str = "req_specification",
        work_item_type: str = "requirement",
        structure_link_role: str = "has_parent",
        space_id: str = "_default",
        style_map: dict[str, str] | None = None,
        attribute_map: dict[str, str] | None = None,
        relation_map: dict[str, str] | None = None,
        dry_run: bool = True,
    ) -> dict[str, Any]:
        """Import a Word (.docx) or ReqIF file into Polarion as a LiveDoc.

        Word files use Polarion's REST importWordDocument action (native
        LiveDoc conversion). ReqIF still uses local parse-and-create.
        Always run with dry_run=true first. Set dry_run=false to write.
        Result `portal_url` opens the LiveDoc; call list_document_parts after.
        """
        suffix = Path(file_path).suffix.lower()
        try:
            preview = parse_import_file(
                file_path,
                module_name=module_name,
                title=title,
                work_item_type=work_item_type,
                structure_link_role=structure_link_role,
                style_map=style_map,
                attribute_map=attribute_map,
                relation_map=relation_map,
            )
        except PolarionError as exc:
            raise RuntimeError(str(exc)) from None

        plan = preview.documents[0] if preview.documents else None
        resolved_name = module_name or (plan.module_name if plan else None) or Path(file_path).stem
        resolved_title = title or (plan.title if plan else None) or resolved_name

        if suffix == ".docx":
            if dry_run:
                return {
                    "dry_run": True,
                    **preview.to_dict(),
                    "mode": "polarion_word_import",
                    "parameters": {
                        "documentName": resolved_name,
                        "documentType": document_type,
                        "title": resolved_title,
                    },
                    "lossy_local_parse": preview.to_dict(),
                }
            try:
                return client_factory().import_word_document(
                    project_id,
                    file_path,
                    module_name=resolved_name,
                    document_type=document_type,
                    title=resolved_title,
                    space_id=space_id,
                    dry_run=False,
                )
            except PolarionError as exc:
                raise RuntimeError(str(exc)) from None

        if dry_run:
            return {"dry_run": True, "mode": "local_parse", **preview.to_dict()}

        try:
            client = client_factory()
            return apply_import_preview(
                client,
                project_id,
                preview,
                source_path=file_path,
                document_type=document_type,
                structure_link_role=structure_link_role,
                space_id=space_id,
            )
        except PolarionError as exc:
            raise RuntimeError(str(exc)) from None

    @mcp.tool
    def list_requirement_blocks(
        project_id: str,
        document_name: str,
        sections: list[str],
        space_id: str = "_default",
        heading_level: int = 2,
    ) -> dict[str, Any]:
        """List requirement blocks parsed from a LiveDoc after import.

        Call import_document first, then pass H1 section titles (for example
        Functional, Physical). Fetches document parts with internal paging and
        returns H2 headings under those sections with body text and part ids.
        """
        try:
            blocks = client_factory().list_requirement_blocks(
                project_id,
                document_name,
                sections,
                space_id=space_id,
                heading_level=heading_level,
            )
        except PolarionError as exc:
            raise RuntimeError(str(exc)) from None
        return {"blocks": [block.to_dict() for block in blocks]}

    @mcp.tool
    def convert_headings_to_work_items(
        project_id: str,
        document_name: str,
        items: list[dict[str, Any]],
        type: str,
        space_id: str = "_default",
        dry_run: bool = True,
    ) -> dict[str, Any]:
        """Batch convert LiveDoc heading parts to a work-item type.

        Each item needs `part_id` (heading part id). Optional `description`
        (HTML) and `delete_part_ids` (text parts to remove after convert).
        `type` is the target Polarion work-item type id. Prefer
        promote_document_requirements for the full import-to-hardware flow; this
        tool is the composable batch convert step.
        """
        try:
            return client_factory().convert_headings_to_work_items(
                project_id,
                document_name,
                items,
                type,
                space_id=space_id,
                dry_run=dry_run,
            )
        except PolarionError as exc:
            raise RuntimeError(str(exc)) from None

    @mcp.tool
    def promote_document_requirements(
        project_id: str,
        document_name: str,
        sections: list[str],
        type: str,
        space_id: str = "_default",
        assign: bool = True,
        delete_source_text: bool = True,
        user_ids: list[str] | None = None,
        dry_run: bool = True,
    ) -> dict[str, Any]:
        """Insert LiveDoc requirement text as work items of `type`.

        After import_document, keeps H2 headings as headings. For each
        promotable block under the given H1 `sections`, creates a work item
        in the document Recycle Bin, inserts a workitem part after the
        heading (`previousPart`), fills description with Global ID, Project
        ID, and body text, and round-robin assigns. Source paragraphs are
        deleted unless `delete_source_text` is false. Skips empty headings
        and blocks that already have a marked work item. Default dry_run=true;
        set dry_run=false to apply.
        """
        try:
            return client_factory().promote_document_requirements(
                project_id,
                document_name,
                sections,
                type,
                space_id=space_id,
                assign=assign,
                delete_source_text=delete_source_text,
                user_ids=user_ids,
                dry_run=dry_run,
            )
        except PolarionError as exc:
            raise RuntimeError(str(exc)) from None
