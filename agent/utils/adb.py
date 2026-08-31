"""Small wrapper around the Android Debug Bridge used by the agent."""

from __future__ import annotations

import base64
import re
import subprocess
from pathlib import Path

from ..apps import get_package_name


class AdbController:
    def __init__(self, adb: Path, serial: str) -> None:
        self.adb = adb
        self.serial = serial

    def _base(self, *args: str) -> list[str]:
        return [str(self.adb), "-s", self.serial, *args]

    def run(self, *args: str, text: bool = True) -> subprocess.CompletedProcess:
        return subprocess.run(self._base(*args), capture_output=True, text=text)

    def shell(self, *args: str) -> subprocess.CompletedProcess[str]:
        return self.run("shell", *args)

    def exec_out(self, *args: str) -> subprocess.CompletedProcess[bytes]:
        return self.run("exec-out", *args, text=False)

    def list_display_ids(self) -> list[int]:
        result = self.shell("dumpsys", "display")
        if result.returncode != 0:
            raise RuntimeError(result.stderr.strip())
        ids = [int(value) for value in re.findall(r"dispId:\s*(\d+)", result.stdout)]
        return sorted(set(ids))

    def screencap(self, output: Path, display_id: int | None = None) -> Path:
        args = ["screencap"]
        if display_id is not None:
            args.extend(["-d", str(display_id)])
        args.append("-p")
        result = self.exec_out(*args)
        if result.returncode != 0:
            raise RuntimeError(result.stderr.decode(errors="ignore"))
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_bytes(result.stdout)
        return output

    def tap(self, x: int, y: int, display_id: int | None = None) -> None:
        args = ["input"]
        if display_id is not None:
            args.extend(["-d", str(display_id)])
        args.extend(["tap", str(x), str(y)])
        result = self.shell(*args)
        if result.returncode != 0:
            raise RuntimeError(result.stderr.strip())

    def swipe(
        self,
        start_x: int,
        start_y: int,
        end_x: int,
        end_y: int,
        duration_ms: int = 300,
        display_id: int | None = None,
    ) -> None:
        args = ["input"]
        if display_id is not None:
            args.extend(["-d", str(display_id)])
        args.extend(
            [
                "swipe",
                str(start_x),
                str(start_y),
                str(end_x),
                str(end_y),
                str(duration_ms),
            ]
        )
        result = self.shell(*args)
        if result.returncode != 0:
            raise RuntimeError(result.stderr.strip())

    def long_press(
        self,
        x: int,
        y: int,
        duration_ms: int = 1000,
        display_id: int | None = None,
    ) -> None:
        self.swipe(x, y, x, y, duration_ms=duration_ms, display_id=display_id)

    def double_tap(
        self,
        x: int,
        y: int,
        display_id: int | None = None,
    ) -> None:
        self.tap(x, y, display_id=display_id)
        self.tap(x, y, display_id=display_id)

    def launch_app(self, app_name: str, display_id: int | None = None) -> None:
        package = get_package_name(app_name)
        if not package:
            raise RuntimeError(f"未找到应用：{app_name}")

        resolve = self.shell(
            "cmd",
            "package",
            "resolve-activity",
            "--brief",
            "-a",
            "android.intent.action.MAIN",
            "-c",
            "android.intent.category.LAUNCHER",
            package,
        )
        if resolve.returncode != 0:
            raise RuntimeError(resolve.stderr.strip())

        component = None
        for line in reversed(resolve.stdout.splitlines()):
            line = line.strip()
            if "/" in line and not line.startswith("priority"):
                component = line
                break
        if not component:
            raise RuntimeError(f"无法解析应用组件：{app_name}")

        args = ["am", "start"]
        if display_id is not None:
            args.extend(["--display", str(display_id)])
        args.extend(["-n", component])
        result = self.shell(*args)
        if result.returncode != 0:
            raise RuntimeError(result.stderr.strip())

    def input_text(self, text: str, display_id: int | None = None) -> None:
        if any(ord(char) > 127 for char in text):
            self._input_text_unicode(text)
            return
        args = ["input"]
        if display_id is not None:
            args.extend(["-d", str(display_id)])
        args.extend(["text", text])
        result = self.shell(*args)
        if result.returncode != 0:
            raise RuntimeError(result.stderr.strip())

    def _input_text_unicode(self, text: str) -> None:
        encoded = base64.b64encode(text.encode("utf-8")).decode("ascii")
        result = self.shell(
            "am",
            "broadcast",
            "-a",
            "ADB_INPUT_B64",
            "--es",
            "msg",
            encoded,
        )
        if result.returncode != 0:
            raise RuntimeError(result.stderr.strip())

    def keyevent(self, code: int, display_id: int | None = None) -> None:
        args = ["input"]
        if display_id is not None:
            args.extend(["-d", str(display_id)])
        args.extend(["keyevent", str(code)])
        result = self.shell(*args)
        if result.returncode != 0:
            raise RuntimeError(result.stderr.strip())

    def home(self) -> None:
        self.keyevent(3)

    def dump_ui(self, output: Path) -> Path:
        remote = "/sdcard/ui.xml"
        result = self.shell("uiautomator", "dump", remote)
        if result.returncode != 0:
            raise RuntimeError(result.stderr.strip())
        pull = self.run("pull", remote, str(output))
        if pull.returncode != 0:
            raise RuntimeError(pull.stderr.strip())
        return output
