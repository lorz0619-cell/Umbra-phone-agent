"""Verify that virtual-display actions do not change the main display."""

from __future__ import annotations

import time
from pathlib import Path

from PIL import Image, ImageChops

from ..execution import AdbExecutionBackend, VirtualDisplayBackend
from ..utils.adb import AdbController


def run_isolation_check(
    adb: Path,
    scrcpy: Path,
    serial: str,
    output_dir: Path,
    app: str = "com.android.chromium",
    record: str | None = None,
) -> bool:
    """Return True when the main display is unchanged during virtual actions."""
    output_dir.mkdir(parents=True, exist_ok=True)
    controller = AdbController(adb, serial)
    physical = AdbExecutionBackend(controller, display_id=0)

    controller.shell("am", "start", "-a", "android.settings.SETTINGS")
    time.sleep(1)
    before = output_dir / "main_before.png"
    physical.screencap(before)

    backend = VirtualDisplayBackend(
        controller,
        scrcpy,
        serial,
        app=app,
        record=record,
    )
    backend.__enter__()
    try:
        inner = AdbExecutionBackend(controller, display_id=backend.display_id)
        inner.screencap(output_dir / "virtual_before.png")
        inner.tap(500, 500)
        time.sleep(1)
        inner.screencap(output_dir / "virtual_after.png")
    finally:
        backend.__exit__(None, None, None)

    after = output_dir / "main_after.png"
    physical.screencap(after)

    left = Image.open(before).convert("RGB")
    right = Image.open(after).convert("RGB")
    diff = ImageChops.difference(left, right)
    unchanged = diff.getbbox() is None
    print(f"main display unchanged: {unchanged}")
    print(f"diff bbox: {diff.getbbox()}")
    return unchanged
