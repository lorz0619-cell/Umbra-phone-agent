"""Helpers for reading Android uiautomator XML dumps."""

from __future__ import annotations

import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class UiElement:
    text: str
    content_desc: str
    resource_id: str
    bounds: tuple[int, int, int, int]

    @property
    def center(self) -> tuple[int, int]:
        left, top, right, bottom = self.bounds
        return ((left + right) // 2, (top + bottom) // 2)


def _parse_bounds(raw: str) -> tuple[int, int, int, int]:
    clean = raw.strip("[]")
    left_top, right_bottom = clean.split("][")
    left, top = (int(value) for value in left_top.split(","))
    right, bottom = (int(value) for value in right_bottom.split(","))
    return left, top, right, bottom


def parse_ui(path: Path) -> list[UiElement]:
    """Return visible text/description elements from a uiautomator XML."""
    root = ET.parse(path).getroot()
    elements: list[UiElement] = []
    for node in root.iter("node"):
        text = node.attrib.get("text", "").strip()
        desc = node.attrib.get("content-desc", "").strip()
        resource_id = node.attrib.get("resource-id", "")
        if not text and not desc and not resource_id:
            continue
        bounds = _parse_bounds(node.attrib.get("bounds", "[0,0][0,0]"))
        elements.append(
            UiElement(
                text=text,
                content_desc=desc,
                resource_id=resource_id,
                bounds=bounds,
            )
        )
    return elements


def find_by_text(elements: list[UiElement], needle: str) -> list[UiElement]:
    """Find elements whose text or content description contains a needle."""
    lowered = needle.casefold()
    matches = [
        item
        for item in elements
        if lowered in item.text.casefold() or lowered in item.content_desc.casefold()
    ]
    return matches


def find_by_resource_id(elements: list[UiElement], needle: str) -> list[UiElement]:
    """Find elements whose resource id contains a needle."""
    lowered = needle.casefold()
    return [item for item in elements if lowered in item.resource_id.casefold()]
