"""Execution backends for physical and virtual Android displays."""

from __future__ import annotations

import time
from pathlib import Path
from typing import Protocol

from .utils.adb import AdbController
from .utils.browser import BrowserProvider
from .utils.chrome_cdp import ChromeCdpController
from .utils.scrcpy import VirtualDisplaySession


class ExecutionBackend(Protocol):
    """Common action surface used by Agent tasks."""

    def open_url(self, url: str) -> None:
        """Open a URL in the device browser."""

    def tap(self, x: int, y: int) -> None:
        """Tap a coordinate."""

    def swipe(
        self,
        start_x: int,
        start_y: int,
        end_x: int,
        end_y: int,
    ) -> None:
        """Swipe from start to end."""

    def long_press(self, x: int, y: int) -> None:
        """Long-press a coordinate."""

    def double_tap(self, x: int, y: int) -> None:
        """Double-tap a coordinate."""

    def launch_app(self, app_name: str) -> None:
        """Launch an app by display name or package."""

    def input_text(self, text: str) -> None:
        """Type text into the focused field."""

    def keyevent(self, code: int) -> None:
        """Send a key event."""

    def home(self) -> None:
        """Navigate to the home screen."""

    def screencap(self, output: Path) -> Path:
        """Capture the current screen."""

    def dump_ui(self, output: Path) -> Path:
        """Dump the current UI hierarchy."""


class AdbExecutionBackend:
    """Backend that executes through ADB on a specific display."""

    def __init__(
        self,
        controller: AdbController,
        display_id: int | None = None,
    ) -> None:
        self.controller = controller
        self.display_id = display_id
        self.browser = BrowserProvider(controller)

    def open_url(self, url: str) -> None:
        self.browser.open_url(url, display_id=self.display_id)

    def tap(self, x: int, y: int) -> None:
        self.controller.tap(x, y, display_id=self.display_id)

    def swipe(
        self,
        start_x: int,
        start_y: int,
        end_x: int,
        end_y: int,
    ) -> None:
        self.controller.swipe(
            start_x,
            start_y,
            end_x,
            end_y,
            display_id=self.display_id,
        )

    def long_press(self, x: int, y: int) -> None:
        self.controller.long_press(x, y, display_id=self.display_id)

    def double_tap(self, x: int, y: int) -> None:
        self.controller.double_tap(x, y, display_id=self.display_id)

    def launch_app(self, app_name: str) -> None:
        self.controller.launch_app(app_name, display_id=self.display_id)

    def input_text(self, text: str) -> None:
        self.controller.input_text(text, display_id=self.display_id)

    def keyevent(self, code: int) -> None:
        self.controller.keyevent(code, display_id=self.display_id)

    def home(self) -> None:
        self.keyevent(3)

    def screencap(self, output: Path) -> Path:
        return self.controller.screencap(output, display_id=self.display_id)

    def dump_ui(self, output: Path) -> Path:
        return self.controller.dump_ui(output)


class VirtualDisplayBackend:
    """Manage a scrcpy virtual display and delegate actions to it."""

    def __init__(
        self,
        controller: AdbController,
        scrcpy: Path,
        serial: str,
        app: str | None = None,
        size: str = "1920x1080",
        record: str | None = None,
        time_limit: int | None = None,
    ) -> None:
        self.controller = controller
        self.app = app
        self.session = VirtualDisplaySession(
            scrcpy,
            serial,
            adb=controller.adb,
            app=app,
            size=size,
            record=record,
            time_limit=time_limit,
        )
        self.chrome_cdp = ChromeCdpController(controller)
        self.display_id: int | None = None
        self._inner: AdbExecutionBackend | None = None

    def __enter__(self) -> "VirtualDisplayBackend":
        if self.app in BrowserProvider.CHROMIUM_PACKAGES:
            self.chrome_cdp.ensure_command_line()
            self.chrome_cdp.force_stop_browser()
        self.display_id = self.session.start()
        self._inner = AdbExecutionBackend(
            self.controller,
            display_id=self.display_id,
        )
        if self.app in BrowserProvider.CHROMIUM_PACKAGES:
            self.chrome_cdp.wait_for_page(timeout=15)
            time.sleep(1)
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        self.session.stop()

    @property
    def inner(self) -> AdbExecutionBackend:
        if self._inner is None:
            raise RuntimeError("VirtualDisplayBackend 尚未启动")
        return self._inner

    def tap(self, x: int, y: int) -> None:
        if self.app in BrowserProvider.CHROMIUM_PACKAGES:
            try:
                self.chrome_cdp.click(x, y)
                return
            except Exception:
                pass
        self.inner.tap(x, y)

    def swipe(
        self,
        start_x: int,
        start_y: int,
        end_x: int,
        end_y: int,
    ) -> None:
        if self.app in BrowserProvider.CHROMIUM_PACKAGES:
            self.chrome_cdp.swipe(start_x, start_y, end_x, end_y)
            return
        self.inner.swipe(start_x, start_y, end_x, end_y)

    def long_press(self, x: int, y: int) -> None:
        if self.app in BrowserProvider.CHROMIUM_PACKAGES:
            self.chrome_cdp.swipe(x, y, x, y)
            return
        self.inner.long_press(x, y)

    def double_tap(self, x: int, y: int) -> None:
        if self.app in BrowserProvider.CHROMIUM_PACKAGES:
            self.chrome_cdp.click(x, y)
            self.chrome_cdp.click(x, y)
            return
        self.inner.double_tap(x, y)

    def launch_app(self, app_name: str) -> None:
        self.inner.launch_app(app_name)

    def input_text(self, text: str) -> None:
        if self.app in BrowserProvider.CHROMIUM_PACKAGES:
            try:
                self.chrome_cdp.type_text(text)
                return
            except Exception:
                pass
        self.inner.input_text(text)

    def keyevent(self, code: int) -> None:
        if self.app in BrowserProvider.CHROMIUM_PACKAGES:
            if code == 66:
                self.chrome_cdp.press_enter()
                return
            if code == 4:
                self.chrome_cdp.back()
                return
        self.inner.keyevent(code)

    def home(self) -> None:
        if self.app in BrowserProvider.CHROMIUM_PACKAGES:
            self.chrome_cdp.navigate("about:blank")
            return
        self.inner.home()

    def screencap(self, output: Path) -> Path:
        if self.app in BrowserProvider.CHROMIUM_PACKAGES:
            try:
                return self.chrome_cdp.capture_screenshot(output)
            except Exception:
                # Virtual-display ADB screenshots are empty on MuMu, but this
                # fallback keeps isolation checks from aborting when CDP is busy.
                return self.inner.screencap(output)
        return self.inner.screencap(output)

    def dump_ui(self, output: Path) -> Path:
        return self.inner.dump_ui(output)

    def open_url(self, url: str) -> None:
        self.chrome_cdp.navigate(url)
        if self.app in BrowserProvider.CHROMIUM_PACKAGES:
            time.sleep(1.5)


def create_backend(
    mode: str,
    controller: AdbController,
    scrcpy: Path | None = None,
    serial: str | None = None,
    display_id: int | None = None,
    app: str | None = None,
    size: str = "1920x1080",
    record: str | None = None,
    time_limit: int | None = None,
) -> ExecutionBackend:
    """Create a physical or virtual execution backend."""
    if mode == "physical":
        return AdbExecutionBackend(controller, display_id=display_id)
    if mode == "virtual":
        if scrcpy is None or serial is None:
            raise ValueError("virtual backend requires scrcpy and serial")
        return VirtualDisplayBackend(
            controller,
            scrcpy,
            serial,
            app=app,
            size=size,
            record=record,
            time_limit=time_limit,
        )
    raise ValueError(f"未知 backend 模式：{mode}")
