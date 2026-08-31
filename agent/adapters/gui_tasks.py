"""GUI tasks executed on the MuMu/Android device through ADB."""

from __future__ import annotations

import time
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import quote

from ..execution import ExecutionBackend
from ..utils.ui import find_by_resource_id, parse_ui


@dataclass(frozen=True)
class GuiResult:
    message: str
    screenshot: Path | None = None

    def as_text(self) -> str:
        if self.screenshot:
            return f"{self.message}，截图已保存到 {self.screenshot}"
        return self.message


def open_web(
    backend: ExecutionBackend,
    url: str,
    screenshot_path: Path,
) -> GuiResult:
    """Open a URL in the device browser and capture the resulting screen."""
    try:
        backend.open_url(url)
    except Exception as exc:
        return GuiResult(message=f"打开网页失败：{exc}")

    time.sleep(1.5)
    try:
        backend.screencap(screenshot_path)
    except Exception:
        return GuiResult(message="网页已打开，但截图失败")
    return GuiResult(message=f"已打开 {url}", screenshot=screenshot_path)


def search_on_launcher(
    backend: ExecutionBackend,
    query: str,
    screenshot_path: Path,
) -> GuiResult:
    """Run a short multi-step GUI flow using the MuMu launcher search bar.

    ADB's built-in ``input text`` command only handles ASCII reliably. For
    Chinese text we should switch to ADBKeyboard later.
    """
    backend.home()
    time.sleep(0.5)

    ui_path = screenshot_path.with_suffix(".xml")
    backend.dump_ui(ui_path)
    elements = parse_ui(ui_path)
    search_bars = find_by_resource_id(elements, "app.lawnchair:id/searchBar")
    if not search_bars:
        return GuiResult(message="未找到桌面搜索框")
    x, y = search_bars[0].center
    backend.tap(x, y)
    time.sleep(0.5)
    backend.input_text(query)
    time.sleep(0.3)
    backend.keyevent(66)
    time.sleep(1.5)

    backend.screencap(screenshot_path)
    return GuiResult(message=f"已在桌面搜索框执行查询：{query}", screenshot=screenshot_path)


def browser_search(
    backend: ExecutionBackend,
    query: str,
    screenshot_path: Path,
) -> GuiResult:
    """Search using the browser URL, avoiding device-specific search widgets."""
    url = f"https://www.baidu.com/s?wd={quote(query)}"
    return open_web(backend, url, screenshot_path)
