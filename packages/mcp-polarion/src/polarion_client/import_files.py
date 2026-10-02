"""Parse Word (.docx) and ReqIF (.reqif / .reqifz) files for Polarion document import.

Word outline levels: Heading N (or outline level N-1) maps to ImportWorkItem.level = N
(Heading 1 → 1). Parent work item is the nearest preceding item with a smaller level.
"""

from __future__ import annotations

import re
import zipfile
from dataclasses import dataclass
from html import escape
from pathlib import Path
from xml.etree import ElementTree as ET

from polarion_client.errors import PolarionError

MAX_IMPORT_BYTES = 50 * 1024 * 1024
SUPPORTED_SUFFIXES = {".docx", ".reqif", ".reqifz"}

REQIF_NS = "http://www.omg.org/spec/ReqIF/20110401"
REQIF = f"{{{REQIF_NS}}}"

_IMAGE_CONTENT_TYPES = {
    "image/png": "png",
    "image/jpeg": "jpg",
    "image/jpg": "jpg",
    "image/gif": "gif",
}

# Word: Heading 1 → level 1, Heading 2 → level 2, … (smaller level = ancestor in outline).
_WORD_HEADING_RE = re.compile(r"^heading\s*(\d+)$", re.IGNORECASE)
_SKIP_STYLE_RE = re.compile(r"^(toc|header|footer)", re.IGNORECASE)


@dataclass(frozen=True)
class ImportImage:
    filename: str
    content_type: str
    data: bytes
    target: str
    work_item_key: str | None


@dataclass(frozen=True)
class ImportWorkItem:
    key: str
    wi_type: str
    title: str
    description_html: str
    level: int
    parent_key: str | None
    status: str | None
    severity: str | None
    extra_attributes: dict[str, str]
    reqif_id: str | None


@dataclass(frozen=True)
class ImportLink:
    source_key: str
    target_key: str
    role: str | None
    relation_type: str


@dataclass(frozen=True)
class ImportDocumentPlan:
    module_name: str
    title: str
    home_page_content: str
    work_items: list[ImportWorkItem]
    links: list[ImportLink]
    images: list[ImportImage]
    warnings: list[str]


@dataclass(frozen=True)
class ImportPreview:
    documents: list[ImportDocumentPlan]
    source_filename: str
    warnings: list[str]

    def to_dict(self) -> dict:
        def image_dict(img: ImportImage) -> dict:
            return {
                "filename": img.filename,
                "content_type": img.content_type,
                "size": len(img.data),
                "target": img.target,
                "work_item_key": img.work_item_key,
            }

        def work_item_dict(wi: ImportWorkItem) -> dict:
            return {
                "key": wi.key,
                "wi_type": wi.wi_type,
                "title": wi.title,
                "description_html": wi.description_html,
                "level": wi.level,
                "parent_key": wi.parent_key,
                "status": wi.status,
                "severity": wi.severity,
                "extra_attributes": dict(wi.extra_attributes),
                "reqif_id": wi.reqif_id,
            }

        def link_dict(link: ImportLink) -> dict:
            return {
                "source_key": link.source_key,
                "target_key": link.target_key,
                "role": link.role,
                "relation_type": link.relation_type,
            }

        def doc_dict(doc: ImportDocumentPlan) -> dict:
            return {
                "module_name": doc.module_name,
                "title": doc.title,
                "home_page_content": doc.home_page_content,
                "work_items": [work_item_dict(w) for w in doc.work_items],
                "links": [link_dict(lnk) for lnk in doc.links],
                "images": [image_dict(img) for img in doc.images],
                "warnings": list(doc.warnings),
            }

        return {
            "documents": [doc_dict(d) for d in self.documents],
            "source_filename": self.source_filename,
            "warnings": list(self.warnings),
        }


def parse_import_file(
    path: str | Path,
    *,
    module_name: str | None = None,
    title: str | None = None,
    work_item_type: str = "requirement",
    structure_link_role: str = "has_parent",
    style_map: dict[str, str] | None = None,
    attribute_map: dict[str, str] | None = None,
    relation_map: dict[str, str] | None = None,
) -> ImportPreview:
    file_path = Path(path)
    if not file_path.is_file():
        raise PolarionError(f"Import file not found: {file_path}")

    suffix = file_path.suffix.lower()
    if suffix not in SUPPORTED_SUFFIXES:
        raise PolarionError(
            f"Unsupported import file type '{suffix}'. "
            f"Supported: {', '.join(sorted(SUPPORTED_SUFFIXES))}"
        )

    size = file_path.stat().st_size
    if size > MAX_IMPORT_BYTES:
        raise PolarionError(
            f"Import file exceeds maximum size ({MAX_IMPORT_BYTES} bytes): {size} bytes"
        )

    style_map = style_map or {}
    attribute_map = attribute_map or {}
    relation_map = relation_map or {}

    if suffix == ".docx":
        return _parse_docx(
            file_path,
            module_name=module_name,
            title=title,
            work_item_type=work_item_type,
            structure_link_role=structure_link_role,
            style_map=style_map,
        )
    return _parse_reqif(
        file_path,
        module_name=module_name,
        title=title,
        work_item_type=work_item_type,
        structure_link_role=structure_link_role,
        attribute_map=attribute_map,
        relation_map=relation_map,
    )


def _should_skip_paragraph_style(style_name: str | None) -> bool:
    if not style_name:
        return False
    name = style_name.strip()
    if _SKIP_STYLE_RE.match(name):
        return True
    upper = name.upper()
    if "TOC" in upper and name.upper().startswith("TOC"):
        return True
    if upper.startswith("HEADER") or upper.startswith("FOOTER"):
        return True
    for part in ("HEADER", "FOOTER"):
        if part in upper and ("PAGE" in upper or name.lower().startswith(part.lower())):
            return True
    return False


def style_map_get(style_name: str, style_map: dict[str, str]) -> str | None:
    if style_name in style_map:
        return style_map[style_name]
    for key, value in style_map.items():
        if key.lower() == style_name.lower():
            return value
    return None


def _paragraph_work_item_type(paragraph, default: str, style_map: dict[str, str]) -> str:
    style_name = paragraph.style.name if paragraph.style else ""
    mapped = style_map_get(style_name, style_map)
    return mapped if mapped else default


def _run_to_html(run) -> str:
    text = escape(run.text or "")
    if not text:
        return ""
    if run.bold:
        text = f"<strong>{text}</strong>"
    if run.italic:
        text = f"<em>{text}</em>"
    if run.underline:
        text = f"<u>{text}</u>"
    return text


def _paragraph_to_html(paragraph) -> str:
    parts = [_run_to_html(run) for run in paragraph.runs]
    inner = "".join(parts) if any(parts) else escape(paragraph.text or "")
    return f"<p>{inner}</p>"


def _table_to_html(table) -> str:
    rows_html: list[str] = []
    for row in table.rows:
        cells = "".join(f"<td>{escape(cell.text)}</td>" for cell in row.cells)
        rows_html.append(f"<tr>{cells}</tr>")
    return f"<table>{''.join(rows_html)}</table>"


def _next_template_id(counter: list[int]) -> str:
    counter[0] += 1
    return f"polarion_template_{counter[0]}"


def _content_type_from_part(part) -> str | None:
    content_type = getattr(part, "content_type", None)
    if content_type in _IMAGE_CONTENT_TYPES:
        return content_type
    partname = str(getattr(part, "partname", "") or "")
    ext = Path(partname).suffix.lower()
    if ext == ".png":
        return "image/png"
    if ext in (".jpg", ".jpeg"):
        return "image/jpeg"
    if ext == ".gif":
        return "image/gif"
    return None


def _extract_docx_images(document, warnings: list[str]) -> dict[str, tuple[str, bytes, str]]:
    """Map relationship id -> (filename, data, content_type)."""
    images: dict[str, tuple[str, bytes, str]] = {}
    idx = 0
    for rel in document.part.rels.values():
        if "image" not in rel.reltype:
            continue
        part = rel.target_part
        content_type = _content_type_from_part(part)
        if content_type is None:
            warnings.append(f"Skipped unsupported embedded image type: {rel.reltype}")
            continue
        ext = _IMAGE_CONTENT_TYPES[content_type]
        idx += 1
        filename = f"image-{idx}.{ext}"
        images[rel.rId] = (filename, part.blob, content_type)
    return images


def _paragraph_image_rids(paragraph) -> list[str]:
    rids: list[str] = []
    blip_tag = "{http://schemas.openxmlformats.org/drawingml/2006/main}blip"
    for blip in paragraph._element.iter(blip_tag):
        embed = blip.get(
            "{http://schemas.openxmlformats.org/officeDocument/2006/relationships}embed"
        )
        if embed:
            rids.append(embed)
    return rids


def _parse_docx(
    file_path: Path,
    *,
    module_name: str | None,
    title: str | None,
    work_item_type: str,
    structure_link_role: str,
    style_map: dict[str, str],
) -> ImportPreview:
    from docx import Document
    from docx.table import Table
    from docx.text.paragraph import Paragraph

    document = Document(str(file_path))
    global_warnings: list[str] = []
    rel_images = _extract_docx_images(document, global_warnings)

    mod_name = _sanitize_module_name(module_name or file_path.stem)
    doc_title = title or file_path.stem

    home_parts: list[str] = []
    template_counter = [0]
    work_items: list[ImportWorkItem] = []
    links: list[ImportLink] = []
    images: list[ImportImage] = []
    doc_warnings: list[str] = []

    current_key: str | None = None
    current_level: int | None = None
    current_type = work_item_type
    current_title = ""
    current_desc_parts: list[str] = []
    wi_counter = 0
    stack: list[tuple[str, int]] = []

    def flush_work_item() -> None:
        nonlocal current_key, current_title, current_desc_parts, current_level, current_type
        if current_key is None:
            return
        parent_key: str | None = None
        while stack and stack[-1][1] >= (current_level or 1):
            stack.pop()
        if stack:
            parent_key = stack[-1][0]
            links.append(
                ImportLink(
                    source_key=parent_key,
                    target_key=current_key,
                    role=structure_link_role,
                    relation_type="parent",
                )
            )
        stack.append((current_key, current_level or 1))
        work_items.append(
            ImportWorkItem(
                key=current_key,
                wi_type=current_type,
                title=current_title,
                description_html="".join(current_desc_parts),
                level=current_level or 1,
                parent_key=parent_key,
                status=None,
                severity=None,
                extra_attributes={},
                reqif_id=None,
            )
        )
        current_key = None
        current_desc_parts = []

    def append_home(html: str) -> None:
        pid = _next_template_id(template_counter)
        home_parts.append(f'<p id="{pid}">{html.removeprefix("<p>").removesuffix("</p>")}</p>')

    body = document.element.body
    for child in body.iterchildren():
        tag = child.tag.split("}")[-1]
        if tag == "tbl":
            table = Table(child, document)
            html = _table_to_html(table)
            if current_key is None:
                append_home(html)
            else:
                current_desc_parts.append(html)
            continue
        if tag != "p":
            continue
        paragraph = Paragraph(child, document)
        style_name = paragraph.style.name if paragraph.style else ""
        if _should_skip_paragraph_style(style_name):
            continue

        heading_level = None
        match = _WORD_HEADING_RE.match((style_name or "").strip())
        if match:
            heading_level = min(int(match.group(1)), 9)
        else:
            p_pr = paragraph._element.pPr
            if p_pr is not None and p_pr.outlineLvl is not None:
                heading_level = min(int(p_pr.outlineLvl.val) + 1, 9)
            elif style_map_get(style_name, style_map):
                heading_level = 1

        if heading_level is not None:
            flush_work_item()
            wi_counter += 1
            current_key = f"wi-{wi_counter}"
            current_level = heading_level
            current_type = _paragraph_work_item_type(paragraph, work_item_type, style_map)
            current_title = (paragraph.text or "").strip()
            current_desc_parts = []
            for rid in _paragraph_image_rids(paragraph):
                if rid in rel_images:
                    filename, data, ctype = rel_images[rid]
                    images.append(
                        ImportImage(
                            filename=filename,
                            content_type=ctype,
                            data=data,
                            target="work_item",
                            work_item_key=current_key,
                        )
                    )
                    current_desc_parts.append(f'<p><img src="{filename}"></p>')
            continue

        para_html = _paragraph_to_html(paragraph)
        for rid in _paragraph_image_rids(paragraph):
            if rid in rel_images:
                filename, data, ctype = rel_images[rid]
                target = "work_item" if current_key else "document"
                images.append(
                    ImportImage(
                        filename=filename,
                        content_type=ctype,
                        data=data,
                        target=target,
                        work_item_key=current_key,
                    )
                )
                para_html = para_html.replace("</p>", f'<img src="{filename}"></p>', 1)

        if current_key is None:
            pid = _next_template_id(template_counter)
            inner = para_html.removeprefix("<p>").removesuffix("</p>")
            home_parts.append(f'<p id="{pid}">{inner}</p>')
        else:
            current_desc_parts.append(para_html)

    flush_work_item()

    return ImportPreview(
        documents=[
            ImportDocumentPlan(
                module_name=mod_name,
                title=doc_title,
                home_page_content="".join(home_parts),
                work_items=work_items,
                links=links,
                images=images,
                warnings=doc_warnings + global_warnings,
            )
        ],
        source_filename=file_path.name,
        warnings=list(global_warnings),
    )


def _sanitize_module_name(name: str) -> str:
    cleaned = re.sub(r"[^A-Za-z0-9_-]+", "-", name)
    cleaned = re.sub(r"-+", "-", cleaned).strip("-_")
    return cleaned or "import"


def _reqif_local(tag: str) -> str:
    return tag.split("}", 1)[-1] if "}" in tag else tag


def _reqif_find(parent, local_name: str) -> ET.Element | None:
    for child in parent:
        if _reqif_local(child.tag) == local_name:
            return child
    return None


def _reqif_findall(parent, local_name: str) -> list[ET.Element]:
    return [c for c in parent if _reqif_local(c.tag) == local_name]


def _reqif_text(elem: ET.Element | None) -> str:
    if elem is None:
        return ""
    return (elem.text or "").strip()


def _reqif_attr_long_names(root: ET.Element) -> dict[str, str]:
    """Map attribute definition identifier -> LONG-NAME."""
    names: dict[str, str] = {}
    for elem in root.iter():
        local = _reqif_local(elem.tag)
        if not local.startswith("ATTRIBUTE-DEFINITION"):
            continue
        ident = elem.attrib.get("IDENTIFIER") or elem.attrib.get(f"{REQIF}IDENTIFIER")
        if not ident:
            for k, v in elem.attrib.items():
                if k.endswith("IDENTIFIER"):
                    ident = v
                    break
        long_name_el = _reqif_find(elem, "LONG-NAME")
        long_name = _reqif_text(long_name_el) or ident or ""
        if ident:
            names[ident] = long_name
    return names


def _reqif_type_long_names(root: ET.Element) -> dict[str, str]:
    names: dict[str, str] = {}
    for elem in root.iter():
        local = _reqif_local(elem.tag)
        if local != "RELATION-GROUP" and "TYPE" not in local and local not in (
            "RELATION-GROUP-TYPE",
            "SPEC-RELATION-GROUP-TYPE",
        ):
            if local.endswith("-TYPE") or local == "DATATYPE-DEFINITION-STRING":
                ident = elem.attrib.get("IDENTIFIER")
                if not ident:
                    continue
                ln = _reqif_text(_reqif_find(elem, "LONG-NAME"))
                if ln:
                    names[ident] = ln
        if local == "RELATION-GROUP-TYPE" or local.endswith("RELATION-TYPE"):
            ident = elem.attrib.get("IDENTIFIER")
            if ident:
                ln = _reqif_text(_reqif_find(elem, "LONG-NAME"))
                names[ident] = ln or ident
    for elem in root.iter():
        if _reqif_local(elem.tag) == "SPEC-RELATION-TYPE":
            ident = elem.attrib.get("IDENTIFIER")
            if ident:
                ln = _reqif_text(_reqif_find(elem, "LONG-NAME"))
                names[ident] = ln or ident
    return names


def _read_reqif_xml(file_path: Path) -> bytes:
    if file_path.suffix.lower() == ".reqifz":
        with zipfile.ZipFile(file_path, "r") as zf:
            reqif_names = [n for n in zf.namelist() if n.lower().endswith(".reqif")]
            if not reqif_names:
                raise PolarionError(f"No .reqif file found inside {file_path.name}")
            return zf.read(reqif_names[0])
    return file_path.read_bytes()


def _parse_spec_object_values(
    spec_obj: ET.Element,
    attr_names: dict[str, str],
    attribute_map: dict[str, str],
    work_item_type: str,
) -> tuple[str, str, str, str | None, str | None, dict[str, str], list[str]]:
    warnings: list[str] = []
    title = ""
    description_parts: list[str] = []
    status: str | None = None
    severity: str | None = None
    extra: dict[str, str] = {}

    values_parent = _reqif_find(spec_obj, "VALUES")
    if values_parent is None:
        values_parent = spec_obj
    for val in values_parent:
        local = _reqif_local(val.tag)
        if not local.startswith("ATTRIBUTE-VALUE"):
            continue
        def_ref = None
        def_el = _reqif_find(val, "DEFINITION")
        if def_el is not None:
            for child in def_el:
                if _reqif_local(child.tag).endswith("-REF"):
                    def_ref = (child.text or "").strip()
                    break
        if not def_ref:
            for child in val:
                tag_local = _reqif_local(child.tag)
                if tag_local.endswith("-REF") and "DEFINITION" in tag_local:
                    def_ref = (child.text or "").strip()
                    break
        if not def_ref:
            continue
        long_name = attr_names.get(def_ref, def_ref)
        value_text = ""
        the_val = _reqif_find(val, "THE-VALUE")
        if the_val is not None:
            if list(the_val):
                value_text = ET.tostring(the_val, encoding="unicode", method="html")
                value_text = value_text.replace(f"<THE-VALUE>", "").replace("</THE-VALUE>", "")
            else:
                value_text = _reqif_text(the_val)

        ln_lower = long_name.lower()
        mapped = attribute_map.get(long_name) or attribute_map.get(long_name.lower())

        if long_name in ("ReqIF.Name", "ReqIF.ChapterName") or ln_lower in ("reqif.name", "reqif.chaptername"):
            if value_text and not title:
                title = _strip_html(value_text) if "<" in value_text else value_text
            continue
        if long_name == "ReqIF.Text" or ln_lower == "reqif.text":
            description_parts.append(value_text if value_text else "")
            continue
        if mapped == "title" or ln_lower == "title":
            title = _strip_html(value_text) if "<" in value_text else value_text
            continue
        if mapped == "description" or ln_lower == "description":
            description_parts.append(value_text)
            continue
        if mapped == "status" or ln_lower == "status":
            status = _strip_html(value_text)
            continue
        if mapped == "severity" or ln_lower == "severity":
            severity = _strip_html(value_text)
            continue

        plain = _strip_html(value_text) if value_text else ""
        extra[long_name] = plain
        description_parts.append(f"<p><strong>{escape(long_name)}:</strong> {escape(plain)}</p>")

    wi_type = work_item_type
    type_el = _reqif_find(spec_obj, "TYPE")
    if type_el is not None:
        for child in type_el:
            if _reqif_local(child.tag).endswith("-REF"):
                pass

    description_html = "".join(description_parts)
    if not title:
        title = "Untitled"
    return wi_type, title, description_html, status, severity, extra, warnings


def _strip_html(value: str) -> str:
    text = re.sub(r"<[^>]+>", "", value)
    return text.strip()


def _walk_spec_hierarchy(
    hierarchy: ET.Element,
    spec_objects: dict[str, ET.Element],
    attr_names: dict[str, str],
    attribute_map: dict[str, str],
    work_item_type: str,
    structure_link_role: str,
    parent_key: str | None,
    order: list[str],
    work_items: dict[str, ImportWorkItem],
    links: list[ImportLink],
    warnings: list[str],
    level: int,
) -> None:
    obj_ref_el = None
    for child in hierarchy:
        if _reqif_local(child.tag) == "OBJECT":
            obj_ref_el = child
            break
    if obj_ref_el is None:
        return
    ref_el = None
    for child in obj_ref_el:
        if _reqif_local(child.tag) == "SPEC-OBJECT-REF":
            ref_el = child
            break
    if ref_el is None or not (ref_el.text or "").strip():
        return
    obj_id = ref_el.text.strip()
    spec_obj = spec_objects.get(obj_id)
    if spec_obj is None:
        warnings.append(f"SPEC-OBJECT reference not found: {obj_id}")
        return

    key = obj_id
    wi_type, title, description_html, status, severity, extra, _ = _parse_spec_object_values(
        spec_obj, attr_names, attribute_map, work_item_type
    )
    reqif_line = f'<p><strong>ReqIF ID:</strong> {escape(obj_id)}</p>'
    if reqif_line not in description_html:
        description_html = reqif_line + description_html

    if parent_key:
        links.append(
            ImportLink(
                source_key=parent_key,
                target_key=key,
                role=structure_link_role,
                relation_type="parent",
            )
        )

    order.append(key)
    work_items[key] = ImportWorkItem(
        key=key,
        wi_type=wi_type,
        title=title,
        description_html=description_html,
        level=level,
        parent_key=parent_key,
        status=status,
        severity=severity,
        extra_attributes=extra,
        reqif_id=obj_id,
    )

    children_parent = _reqif_find(hierarchy, "CHILDREN")
    if children_parent is None:
        return
    for child_h in _reqif_findall(children_parent, "SPEC-HIERARCHY"):
        _walk_spec_hierarchy(
            child_h,
            spec_objects,
            attr_names,
            attribute_map,
            work_item_type,
            structure_link_role,
            key,
            order,
            work_items,
            links,
            warnings,
            level + 1,
        )


def _map_relation_role(
    type_ref: str | None,
    type_long_name: str | None,
    relation_map: dict[str, str],
    structure_link_role: str,
) -> tuple[str | None, bool]:
    if type_long_name and type_long_name in relation_map:
        return relation_map[type_long_name], True
    if type_ref and type_ref in relation_map:
        return relation_map[type_ref], True
    for key, role in relation_map.items():
        if type_long_name and key.lower() == type_long_name.lower():
            return role, True
    if type_long_name and type_long_name == structure_link_role:
        return structure_link_role, True
    if type_ref and type_ref == structure_link_role:
        return structure_link_role, True
    return None, False


def _parse_reqif(
    file_path: Path,
    *,
    module_name: str | None,
    title: str | None,
    work_item_type: str,
    structure_link_role: str,
    attribute_map: dict[str, str],
    relation_map: dict[str, str],
) -> ImportPreview:
    xml_bytes = _read_reqif_xml(file_path)
    root = ET.fromstring(xml_bytes)

    attr_names = _reqif_attr_long_names(root)
    relation_type_names = _reqif_type_long_names(root)

    spec_objects: dict[str, ET.Element] = {}
    for elem in root.iter():
        if _reqif_local(elem.tag) == "SPEC-OBJECT":
            ident = elem.attrib.get("IDENTIFIER")
            if ident:
                spec_objects[ident] = elem

    spec_relations: list[ET.Element] = [
        e for e in root.iter() if _reqif_local(e.tag) == "SPEC-RELATION"
    ]

    global_warnings: list[str] = []
    documents: list[ImportDocumentPlan] = []

    specifications = [e for e in root.iter() if _reqif_local(e.tag) == "SPECIFICATION"]
    for spec_index, spec in enumerate(specifications):
        spec_id = spec.attrib.get("IDENTIFIER") or f"spec-{spec_index + 1}"
        long_name_el = _reqif_find(spec, "LONG-NAME")
        spec_long_name = _reqif_text(long_name_el) or spec_id
        if module_name and len(specifications) == 1:
            mod = _sanitize_module_name(module_name)
        elif module_name:
            mod = _sanitize_module_name(f"{module_name}-{spec_index + 1}")
        else:
            mod = _sanitize_module_name(spec_long_name)
            if spec_index > 0 and len(specifications) > 1:
                mod = f"{mod}-{spec_index + 1}"

        doc_title = title if title and len(specifications) == 1 else spec_long_name

        work_items_map: dict[str, ImportWorkItem] = {}
        order: list[str] = []
        links: list[ImportLink] = []
        doc_warnings: list[str] = []

        children = _reqif_find(spec, "CHILDREN")
        if children is not None:
            for hier in _reqif_findall(children, "SPEC-HIERARCHY"):
                _walk_spec_hierarchy(
                    hier,
                    spec_objects,
                    attr_names,
                    attribute_map,
                    work_item_type,
                    structure_link_role,
                    None,
                    order,
                    work_items_map,
                    links,
                    doc_warnings,
                    1,
                )

        obj_ids_in_spec = set(order)
        for rel in spec_relations:
            source_ref = target_ref = type_ref = None
            for child in rel:
                ln = _reqif_local(child.tag)
                if ln == "SOURCE":
                    for sub in child:
                        if _reqif_local(sub.tag) == "SPEC-OBJECT-REF":
                            source_ref = (sub.text or "").strip()
                elif ln == "TARGET":
                    for sub in child:
                        if _reqif_local(sub.tag) == "SPEC-OBJECT-REF":
                            target_ref = (sub.text or "").strip()
                elif ln == "TYPE":
                    for sub in child:
                        if _reqif_local(sub.tag).endswith("-REF"):
                            type_ref = (sub.text or "").strip()
            if not source_ref or not target_ref:
                continue
            if source_ref not in obj_ids_in_spec and target_ref not in obj_ids_in_spec:
                continue
            type_long = relation_type_names.get(type_ref or "", type_ref or "")
            role, mapped = _map_relation_role(
                type_ref, type_long, relation_map, structure_link_role
            )
            if not mapped:
                doc_warnings.append(
                    f"Unmapped relation type '{type_long or type_ref}' "
                    f"({source_ref} -> {target_ref})"
                )
            links.append(
                ImportLink(
                    source_key=source_ref,
                    target_key=target_ref,
                    role=role,
                    relation_type=type_long or type_ref or "related",
                )
            )

        documents.append(
            ImportDocumentPlan(
                module_name=mod,
                title=doc_title,
                home_page_content="",
                work_items=[work_items_map[k] for k in order],
                links=links,
                images=[],
                warnings=doc_warnings,
            )
        )

    if not documents:
        global_warnings.append("No SPECIFICATION elements found in ReqIF file")
        documents.append(
            ImportDocumentPlan(
                module_name=_sanitize_module_name(module_name or file_path.stem),
                title=title or file_path.stem,
                home_page_content="",
                work_items=[],
                links=[],
                images=[],
                warnings=[],
            )
        )

    return ImportPreview(
        documents=documents,
        source_filename=file_path.name,
        warnings=global_warnings,
    )
