"""Minimal Chrome DevTools Protocol bridge for virtual-display browser control."""

from __future__ import annotations

import base64
import json
import time
from pathlib import Path
from typing import Any

import requests
import websocket

from .adb import AdbController


class ChromeCdpController:
    """Prepare Chrome remote debugging and navigate an existing page."""

    def __init__(
        self,
        controller: AdbController,
        browser_package: str = "com.android.chromium",
        socket_name: str = "chrome_devtools_remote",
        local_port: int = 9222,
    ) -> None:
        self.controller = controller
        self.browser_package = browser_package
        self.socket_name = socket_name
        self.local_port = local_port
        self.current_target_id: str | None = None

    def ensure_command_line(self) -> None:
        """Write Chrome command-line flags used to enable the abstract CDP socket."""
        content = (
            f"_ --remote-debugging-socket-name={self.socket_name} "
            "--enable-features=NetworkService"
        )
        encoded = base64.b64encode(content.encode("utf-8")).decode("ascii")
        command = (
            f"echo {encoded} | base64 -d > /data/local/tmp/chrome-command-line"
        )
        result = self.controller.run("shell", command)
        if result.returncode != 0:
            raise RuntimeError(result.stderr.strip())

    def force_stop_browser(self) -> None:
        result = self.controller.shell("am", "force-stop", self.browser_package)
        if result.returncode != 0:
            raise RuntimeError(result.stderr.strip())

    def setup_port(self) -> None:
        result = self.controller.run(
            "forward",
            f"tcp:{self.local_port}",
            f"localabstract:{self.socket_name}",
        )
        if result.returncode != 0:
            raise RuntimeError(result.stderr.strip())

    def list_targets(self) -> list[dict[str, Any]]:
        self.setup_port()
        response = requests.get(
            f"http://127.0.0.1:{self.local_port}/json",
            timeout=3,
        )
        response.raise_for_status()
        return response.json()

    def wait_for_page(
        self,
        timeout: float = 10,
        url: str | None = None,
    ) -> dict[str, Any]:
        deadline = time.time() + timeout
        last_error: Exception | None = None
        while time.time() < deadline:
            try:
                targets = self.list_targets()
                pages = [target for target in targets if target.get("type") == "page"]
                if url:
                    normalized = url.rstrip("/")
                    for target in reversed(pages):
                        target_url = target.get("url", "").rstrip("/")
                        if target_url == normalized:
                            return target
                elif pages:
                    return pages[0]
            except Exception as exc:  # pragma: no cover - retry path
                last_error = exc
                time.sleep(0.5)
        raise RuntimeError(f"未找到 Chrome 页面目标：{last_error}")

    def _connect_page(self, timeout: float = 10) -> websocket.WebSocket:
        target = self._current_target(timeout=timeout)
        ws_url = target.get("webSocketDebuggerUrl")
        if not ws_url:
            raise RuntimeError("目标缺少 webSocketDebuggerUrl")
        return websocket.create_connection(ws_url, timeout=timeout)

    def _current_target(self, timeout: float = 10) -> dict[str, Any]:
        if self.current_target_id:
            try:
                for target in self.list_targets():
                    if target.get("id") == self.current_target_id:
                        return target
            except Exception:
                pass
            self.current_target_id = None
        return self.wait_for_page(timeout=timeout)

    def navigate(self, url: str) -> None:
        self.setup_port()
        response = requests.put(
            f"http://127.0.0.1:{self.local_port}/json/new?"
            f"{requests.utils.quote(url, safe='')}",
            timeout=5,
        )
        response.raise_for_status()
        created = response.json()
        self.current_target_id = created.get("id")

        last_error: Exception | None = None
        for _ in range(3):
            ws = self._connect_page(timeout=15)
            try:
                self._send(ws, 1, "Page.enable")
                self._send(ws, 2, "Page.navigate", {"url": url})
            except Exception as exc:  # pragma: no cover - retry path
                last_error = exc
                time.sleep(0.5)
            finally:
                ws.close()
            try:
                target = self._current_target(timeout=15)
                self.current_target_id = target.get("id")
                return
            except Exception as exc:  # pragma: no cover - retry path
                last_error = exc
                time.sleep(0.5)
        raise RuntimeError(f"Chrome 导航失败：{last_error}")

    def capture_screenshot(self, output: Path) -> Path:
        """Capture the current Chrome page on the virtual display as PNG."""
        last_error: Exception | None = None
        for _ in range(3):
            ws = self._connect_page(timeout=30)
            try:
                self._send(ws, 1, "Page.enable")
                self._send(ws, 2, "Page.bringToFront")
                result = self._send(
                    ws,
                    3,
                    "Page.captureScreenshot",
                    {"format": "png", "fromSurface": True},
                )
                data = result.get("result", {}).get("data")
                if not data:
                    raise RuntimeError("Chrome 未返回截图数据")
                output.parent.mkdir(parents=True, exist_ok=True)
                output.write_bytes(base64.b64decode(data))
                return output
            except Exception as exc:  # pragma: no cover - retry path
                last_error = exc
                time.sleep(0.5)
            finally:
                ws.close()
        raise RuntimeError(f"Chrome 截图失败：{last_error}")
        return output

    def click(self, x: int, y: int) -> None:
        """Click at CSS pixel coordinates in the current page."""
        ws = self._connect_page()
        try:
            self._send(ws, 1, "Input.dispatchMouseEvent", {
                "type": "mousePressed",
                "x": x,
                "y": y,
                "button": "left",
                "clickCount": 1,
            })
            self._send(ws, 2, "Input.dispatchMouseEvent", {
                "type": "mouseReleased",
                "x": x,
                "y": y,
                "button": "left",
                "clickCount": 1,
            })
        finally:
            ws.close()

    def type_text(self, text: str) -> None:
        """Insert text into the focused page element."""
        ws = self._connect_page()
        try:
            self._send(ws, 1, "Input.insertText", {"text": text})
        finally:
            ws.close()

    def press_enter(self) -> None:
        """Send an Enter key event to the focused page element."""
        ws = self._connect_page()
        try:
            self._send(ws, 1, "Input.dispatchKeyEvent", {
                "type": "keyDown",
                "key": "Enter",
                "code": "Enter",
                "windowsVirtualKeyCode": 13,
                "nativeVirtualKeyCode": 13,
            })
            self._send(ws, 2, "Input.dispatchKeyEvent", {
                "type": "keyUp",
                "key": "Enter",
                "code": "Enter",
                "windowsVirtualKeyCode": 13,
                "nativeVirtualKeyCode": 13,
            })
        finally:
            ws.close()

    def swipe(
        self,
        start_x: int,
        start_y: int,
        end_x: int,
        end_y: int,
        steps: int = 12,
    ) -> None:
        """Drag from start to end using mouse events."""
        ws = self._connect_page()
        try:
            self._send(ws, 1, "Input.dispatchMouseEvent", {
                "type": "mousePressed",
                "x": start_x,
                "y": start_y,
                "button": "left",
                "clickCount": 1,
            })
            for step in range(1, steps + 1):
                x = start_x + (end_x - start_x) * step // steps
                y = start_y + (end_y - start_y) * step // steps
                self._send(ws, step + 1, "Input.dispatchMouseEvent", {
                    "type": "mouseMoved",
                    "x": x,
                    "y": y,
                    "button": "left",
                })
            self._send(ws, steps + 2, "Input.dispatchMouseEvent", {
                "type": "mouseReleased",
                "x": end_x,
                "y": end_y,
                "button": "left",
                "clickCount": 1,
            })
        finally:
            ws.close()

    def back(self) -> None:
        """Navigate the current page back in history."""
        ws = self._connect_page()
        try:
            self._send(
                ws,
                1,
                "Runtime.evaluate",
                {"expression": "history.back()", "returnByValue": True},
            )
        finally:
            ws.close()

    def _send(
        self,
        ws: websocket.WebSocket,
        request_id: int,
        method: str,
        params: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        ws.send(
            json.dumps(
                {
                    "id": request_id,
                    "method": method,
                    "params": params or {},
                }
            )
        )
        while True:
            message = json.loads(ws.recv())
            if message.get("id") == request_id:
                return message
