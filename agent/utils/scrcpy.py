"""Helpers for running scrcpy virtual-display sessions."""

from __future__ import annotations

import os
import re
import signal
import subprocess
import sys
import threading
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_SCRCPY = PROJECT_ROOT / "tools" / "scrcpy" / "scrcpy-win64-v4.1" / "scrcpy.exe"


def start_virtual_display(
    scrcpy: Path,
    serial: str,
    app: str | None = None,
    size: str = "1920x1080",
    record: str | None = None,
    time_limit: int | None = None,
) -> subprocess.Popen:
    """Start a scrcpy virtual display and optionally launch an app on it."""
    cmd = [str(scrcpy), f"--new-display={size}", "-s", serial]
    if app:
        cmd.extend(["--start-app", app])
    if record:
        cmd.append("--no-window")
        cmd.extend(["--record", record])
    if time_limit:
        cmd.extend(["--time-limit", str(int(time_limit))])
    return subprocess.Popen(cmd)


class VirtualDisplaySession:
    """Manage a scrcpy virtual display and expose its Android display id."""

    def __init__(
        self,
        scrcpy: Path,
        serial: str,
        adb: Path | None = None,
        app: str | None = None,
        size: str = "1920x1080",
        record: str | None = None,
        time_limit: int | None = None,
    ) -> None:
        self.scrcpy = scrcpy
        self.serial = serial
        self.adb = adb
        self.app = app
        self.size = size
        self.record = record
        self.time_limit = time_limit
        self.display_id: int | None = None
        self.process: subprocess.Popen | None = None
        self._display_ready = threading.Event()
        self._reader: threading.Thread | None = None

    def start(self) -> int:
        cmd = [str(self.scrcpy), f"--new-display={self.size}", "-s", self.serial]
        if self.app:
            cmd.extend(["--start-app", self.app])
        if self.record:
            cmd.extend(["--no-window", "--record", self.record])
        if self.time_limit:
            cmd.extend(["--time-limit", str(int(self.time_limit))])

        env = os.environ.copy()
        if self.adb is not None:
            env["ADB"] = str(self.adb)
        if not env.get("HOME") and env.get("USERPROFILE"):
            env["HOME"] = env["USERPROFILE"]

        self.process = subprocess.Popen(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            encoding="utf-8",
            errors="replace",
            bufsize=1,
            env=env,
            creationflags=(
                subprocess.CREATE_NEW_PROCESS_GROUP if sys.platform == "win32" else 0
            ),
        )
        self._reader = threading.Thread(target=self._read_output, daemon=True)
        self._reader.start()

        if not self._display_ready.wait(timeout=15):
            self.stop()
            raise RuntimeError("未能从 scrcpy 输出中获取虚拟屏 display_id")
        if self.display_id is None:
            self.stop()
            raise RuntimeError("scrcpy 已退出，未创建虚拟屏")
        return self.display_id

    def _read_output(self) -> None:
        assert self.process is not None
        assert self.process.stdout is not None
        for line in self.process.stdout:
            print(line, end="", flush=True)
            match = re.search(r"\(id=(\d+)\)", line)
            if match and self.display_id is None:
                self.display_id = int(match.group(1))
                self._display_ready.set()

    def wait(self) -> int:
        if self.process is None:
            return 1
        return self.process.wait()

    def stop(self) -> None:
        if self.process is None:
            return
        if self.process.poll() is None:
            if self.record and sys.platform == "win32":
                try:
                    self.process.send_signal(signal.CTRL_BREAK_EVENT)
                    self.process.wait(timeout=5)
                    return
                except Exception:
                    pass
            self.process.terminate()
            try:
                self.process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.process.kill()

    def __enter__(self) -> "VirtualDisplaySession":
        self.start()
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        self.stop()
