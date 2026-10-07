"""Tests for LiveDoc requirement block parsing."""

from __future__ import annotations

from polarion_client.models import DocumentPart
from polarion_client.requirement_blocks import (
    RequirementBlock,
    build_description,
    is_promotable,
    normalize_heading,
    parse_requirement_blocks,
    to_html,
)


def _heading(part_id: str, level: int, text: str) -> DocumentPart:
    return DocumentPart(
        id=part_id,
        part_type="heading",
        level=level,
        heading_text=text,
    )


def _normal(part_id: str, text: str) -> DocumentPart:
    return DocumentPart(id=part_id, part_type="normal", text=text)


def test_functional_physical_wifi_and_brand_marking() -> None:
    parts = [
        _heading("h-func", 1, "Functional"),
        _heading("h-wifi", 2, "Wi-Fi"),
        _normal("t1", "Global ID: G-001"),
        _normal("t2", "Project ID: P-100"),
        _normal("t3", "Type: Hardware Mitigation"),
        _normal("t4", "The device shall support WPA3."),
        _heading("h-phys", 1, "Physical"),
        _heading("h-brand", 2, "Brand Marking"),
        _normal("t5", "Logo placement shall meet corporate guidelines."),
    ]
    blocks = parse_requirement_blocks(parts, ["Functional", "Physical"])

    assert len(blocks) == 2
    wifi = blocks[0]
    assert wifi.section == "Functional"
    assert wifi.title == "Wi-Fi"
    assert wifi.heading_part_id == "h-wifi"
    assert wifi.text_part_ids == ["t1", "t2", "t3", "t4"]
    assert wifi.lines == [
        "Global ID: G-001",
        "Project ID: P-100",
        "Type: Hardware Mitigation",
        "The device shall support WPA3.",
    ]
    assert wifi.description == (
        "Global ID: G-001\n"
        "Project ID: P-100\n"
        "\n"
        "The device shall support WPA3."
    )
    assert "<br/>" in wifi.description_html
    assert "Type:" not in wifi.description
    assert is_promotable(wifi)

    brand = blocks[1]
    assert brand.section == "Physical"
    assert brand.title == "Brand Marking"
    assert brand.text_part_ids == ["t5"]


def test_introduction_h2_ignored_functional_h2_counted() -> None:
    parts = [
        _heading("h-intro", 1, "Introduction"),
        _heading("h-ignored", 2, "Should Not Appear"),
        _normal("t-x", "Orphan body"),
        _heading("h-func", 1, "Functional"),
        _heading("h-req", 2, "Counted Requirement"),
        _normal("t-y", "Global ID: G-2"),
        _normal("t-z", "Body text."),
    ]
    blocks = parse_requirement_blocks(parts, ["Functional"])

    assert len(blocks) == 1
    assert blocks[0].title == "Counted Requirement"
    assert blocks[0].section == "Functional"


def test_section_heading_nbsp_and_spaces_match_non_functional() -> None:
    parts = [
        _heading("h-nf", 1, "Non-Functional\u00a0   "),
        _heading("h-perf", 2, "Performance"),
        _normal("t1", "Global ID: G-NF"),
        _normal("t2", "Shall meet latency targets."),
    ]
    blocks = parse_requirement_blocks(parts, ["Non-Functional"])

    assert len(blocks) == 1
    assert blocks[0].section == "Non-Functional"
    assert blocks[0].title == "Performance"


def test_table_like_flattened_text_kept_in_body() -> None:
    parts = [
        _heading("h-func", 1, "Functional"),
        _heading("h-tab", 2, "Table Row"),
        _normal("t1", "Global ID: G-T"),
        _normal("t2", "Col A | Col B | Col C"),
        _normal("t3", "val1 | val2 | val3"),
    ]
    blocks = parse_requirement_blocks(parts, ["Functional"])

    assert blocks[0].lines[-2:] == ["Col A | Col B | Col C", "val1 | val2 | val3"]
    assert "Col A | Col B | Col C" in blocks[0].description


def test_is_promotable_requires_global_id_and_body() -> None:
    empty_heading = RequirementBlock(
        section="Functional",
        title="Empty",
        heading_part_id="h1",
        work_item_id=None,
        text_part_ids=[],
        lines=[],
        description="",
        description_html="",
    )
    assert is_promotable(empty_heading) is False

    gid_only = RequirementBlock(
        section="Functional",
        title="GID",
        heading_part_id="h2",
        work_item_id=None,
        text_part_ids=["t1"],
        lines=["Global ID: G-1"],
        description=build_description(["Global ID: G-1"]),
        description_html="",
    )
    assert is_promotable(gid_only) is False

    full = RequirementBlock(
        section="Functional",
        title="Full",
        heading_part_id="h3",
        work_item_id=None,
        text_part_ids=["t1", "t2"],
        lines=["Global ID: G-1", "Shall do something."],
        description=build_description(["Global ID: G-1", "Shall do something."]),
        description_html="",
    )
    assert is_promotable(full) is True


def test_last_open_block_flushed_at_end() -> None:
    parts = [
        _heading("h-func", 1, "Functional"),
        _heading("h-last", 2, "Trailing"),
        _normal("t1", "Global ID: G-LAST"),
        _normal("t2", "Final shall statement."),
    ]
    blocks = parse_requirement_blocks(parts, ["Functional"])

    assert len(blocks) == 1
    assert blocks[0].title == "Trailing"
    assert blocks[0].lines[-1] == "Final shall statement."


def test_normalize_heading_and_to_html() -> None:
    assert normalize_heading("  foo&nbsp;\xa0  bar  ") == "foo bar"
    assert to_html("a\nb") == "a<br/>b"
    assert "&" in to_html("a & b")
