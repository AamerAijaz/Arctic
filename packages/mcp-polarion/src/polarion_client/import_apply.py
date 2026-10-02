"""Apply a parsed import preview to Polarion (shared by MCP tools and CLI)."""

from __future__ import annotations

from pathlib import Path
from typing import TYPE_CHECKING, Any

from polarion_client.import_files import ImportPreview
from polarion_client.models import CreatedDocument, CreatedDocumentWorkItem

if TYPE_CHECKING:
    from polarion_client.client import PolarionClient


def _work_item_path_id(polarion_id: str) -> str:
    """Map API ids like ELK/ELK-42 to path ids like ELK-42."""
    if "/" in polarion_id:
        return polarion_id.split("/", 1)[1]
    return polarion_id


def apply_import_preview(
    client: PolarionClient,
    project_id: str,
    preview: ImportPreview,
    *,
    source_path: str | Path,
    document_type: str,
    structure_link_role: str,
    space_id: str = "_default",
) -> dict[str, Any]:
    """Create documents, work items, attachments, and links from an import preview."""
    path = Path(source_path)
    source_bytes = path.read_bytes()
    source_filename = preview.source_filename or path.name

    all_warnings = list(preview.warnings)
    result_documents: list[dict[str, Any]] = []

    for plan in preview.documents:
        all_warnings.extend(plan.warnings)

        created_doc = client.create_document(
            project_id,
            plan.module_name,
            title=plan.title or None,
            document_type=document_type,
            structure_link_role=structure_link_role,
            home_page_content=plan.home_page_content or None,
            space_id=space_id,
            dry_run=False,
        )
        if not isinstance(created_doc, CreatedDocument):
            raise RuntimeError("Unexpected create_document result during import.")
        document_name = plan.module_name

        client.upload_document_attachment(
            project_id,
            document_name,
            source_filename,
            source_bytes,
            space_id=space_id,
            content_type="application/octet-stream",
        )

        key_to_wi_id: dict[str, str] = {}
        work_item_ids: list[str] = []

        for wi in plan.work_items:
            created_wi = client.create_document_work_item(
                project_id,
                document_name,
                wi.wi_type,
                wi.title,
                space_id=space_id,
                description=wi.description_html or None,
                status=wi.status,
                severity=wi.severity,
                dry_run=False,
            )
            if not isinstance(created_wi, CreatedDocumentWorkItem):
                raise RuntimeError("Unexpected create_document_work_item result during import.")
            key_to_wi_id[wi.key] = created_wi.id
            work_item_ids.append(created_wi.id)

        for image in plan.images:
            if image.target == "document":
                client.upload_document_attachment(
                    project_id,
                    document_name,
                    image.filename,
                    image.data,
                    space_id=space_id,
                    content_type=image.content_type,
                )
            elif image.target == "work_item" and image.work_item_key:
                full_id = key_to_wi_id.get(image.work_item_key)
                if full_id is None:
                    all_warnings.append(
                        f"Skipped image {image.filename}: unknown work item key "
                        f"{image.work_item_key!r}"
                    )
                    continue
                client.upload_work_item_attachment(
                    project_id,
                    _work_item_path_id(full_id),
                    image.filename,
                    image.data,
                    content_type=image.content_type,
                )

        for link in plan.links:
            if link.role is None:
                continue
            source_full = key_to_wi_id.get(link.source_key)
            target_full = key_to_wi_id.get(link.target_key)
            if source_full is None or target_full is None:
                all_warnings.append(
                    f"Skipped link {link.source_key} -> {link.target_key}: missing work item"
                )
                continue
            client.create_work_item_link(
                project_id,
                _work_item_path_id(source_full),
                _work_item_path_id(target_full),
                link.role,
                dry_run=False,
            )

        result_documents.append(
            {
                "id": created_doc.id,
                "portal_url": created_doc.portal_url,
                "work_item_ids": work_item_ids,
            }
        )

    return {"documents": result_documents, "warnings": all_warnings}
