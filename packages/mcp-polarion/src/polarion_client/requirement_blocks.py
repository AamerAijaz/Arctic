"""Parse Polarion LiveDoc document parts into requirement blocks."""

from __future__ import annotations

import html
import re
from collections.abc import Sequence
from dataclasses import dataclass
from typing import Any

from polarion_client.models import DocumentPart

_WHITESPACE_RE = re.compile(r"\s+")


def normalize_heading(text: str | None) -> str:
    if text is None:
        return ""
    cleaned = text.replace("&nbsp;", " ").replace("\xa0", " ")
    return _WHITESPACE_RE.sub(" ", cleaned.strip())


def _clean_line_text(text: str | None) -> str:
    if text is None:
        return ""
    return text.replace("&nbsp;", " ").replace("\xa0", " ").strip()


def build_description(lines: list[str]) -> str:
    global_id: str | None = None
    project_id: str | None = None
    body_lines: list[str] = []

    for line in lines:
        if line.startswith("Global ID:"):
            global_id = line
        elif line.startswith("Project ID:"):
            project_id = line
        elif line.startswith("Type:"):
            continue
        else:
            body_lines.append(line)

    while body_lines and not body_lines[0].strip():
        body_lines.pop(0)
    while body_lines and not body_lines[-1].strip():
        body_lines.pop()

    parts: list[str] = []
    if global_id is not None:
        parts.append(global_id)
    if project_id is not None:
        parts.append(project_id)
    if parts and body_lines:
        parts.append("")
    parts.extend(body_lines)
    return "\n".join(parts)


def to_html(text: str) -> str:
    return html.escape(text, quote=False).replace("\n", "<br/>")


@dataclass(frozen=True)
class RequirementBlock:
    section: str
    title: str
    heading_part_id: str
    work_item_id: str | None
    text_part_ids: list[str]
    lines: list[str]
    description: str
    description_html: str

    def to_dict(self) -> dict[str, Any]:
        return {
            "section": self.section,
            "title": self.title,
            "heading_part_id": self.heading_part_id,
            "work_item_id": self.work_item_id,
            "text_part_ids": list(self.text_part_ids),
            "lines": list(self.lines),
            "description": self.description,
            "description_html": self.description_html,
        }


def is_promotable(block: RequirementBlock) -> bool:
    lines = block.description.split("\n")
    has_global_id = any(line.startswith("Global ID:") for line in lines)
    if not has_global_id:
        return False
    try:
        blank_idx = lines.index("")
    except ValueError:
        return False
    body = "\n".join(lines[blank_idx + 1 :]).strip()
    return bool(body)


def _is_heading(part: DocumentPart) -> bool:
    return part.part_type == "heading"


def _flush_block(
    section: str | None,
    title: str | None,
    heading_part_id: str | None,
    work_item_id: str | None,
    text_part_ids: list[str],
    lines: list[str],
) -> RequirementBlock | None:
    if section is None or title is None or heading_part_id is None:
        return None
    description = build_description(lines)
    return RequirementBlock(
        section=section,
        title=title,
        heading_part_id=heading_part_id,
        work_item_id=work_item_id,
        text_part_ids=list(text_part_ids),
        lines=list(lines),
        description=description,
        description_html=to_html(description),
    )


def parse_requirement_blocks(
    parts: Sequence[DocumentPart],
    sections: Sequence[str],
    *,
    heading_level: int = 2,
) -> list[RequirementBlock]:
    section_by_normalized = {normalize_heading(name): name for name in sections}

    current_section: str | None = None
    blocks: list[RequirementBlock] = []

    block_title: str | None = None
    block_heading_id: str | None = None
    block_work_item_id: str | None = None
    block_text_ids: list[str] = []
    block_lines: list[str] = []

    def close_block() -> None:
        nonlocal block_title, block_heading_id, block_work_item_id, block_text_ids, block_lines
        flushed = _flush_block(
            current_section,
            block_title,
            block_heading_id,
            block_work_item_id,
            block_text_ids,
            block_lines,
        )
        if flushed is not None:
            blocks.append(flushed)
        block_title = None
        block_heading_id = None
        block_work_item_id = None
        block_text_ids = []
        block_lines = []

    for part in parts:
        if _is_heading(part):
            close_block()
            level = part.level
            if level == 1:
                normalized = normalize_heading(part.heading_text)
                current_section = section_by_normalized.get(normalized)
                continue
            if current_section is not None and level == heading_level:
                block_title = _clean_line_text(part.heading_text)
                block_heading_id = part.id
                block_work_item_id = part.work_item_id
            continue

        if block_title is None:
            continue

        part_type = part.part_type
        if part_type == "normal":
            block_text_ids.append(part.id)
            cleaned = _clean_line_text(part.text)
            if cleaned:
                block_lines.append(cleaned)
        elif part_type == "workitem":
            if part.work_item_id and block_work_item_id is None:
                block_work_item_id = part.work_item_id

    close_block()
    return blocks
