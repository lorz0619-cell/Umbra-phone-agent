"""Parse and execute actions returned by the legacy Python VLM prototype."""

from __future__ import annotations

import re
import time
from dataclasses import dataclass
from typing import Any

from .execution import ExecutionBackend


@dataclass(frozen=True)
class Action:
    kind: str
    params: dict[str, Any]
    message: str = ""


_ACTION_PATTERN = re.compile(
    r'(?:do\(action="(?P<action>[^"]+)"(?P<args>[^)]*)\)|'
    r'finish\(message="(?P<message>[^"]*)"\))'
)
_ARG_PATTERN = re.compile(r'(\w+)=("(?:[^"\\]|\\.)*"|\[[^\]]*\]|[-\w.]+)')


def parse_action(text: str) -> Action | None:
    """Return the last do/finish action found in model output."""
    matches = list(_ACTION_PATTERN.finditer(text))
    if not matches:
        return None
    match = matches[-1]
    if match.group("message") is not None:
        return Action(kind="finish", params={}, message=match.group("message"))

    kind = match.group("action")
    raw_args = match.group("args") or ""
    params: dict[str, Any] = {}
    for arg in _ARG_PATTERN.finditer(raw_args):
        key = arg.group(1)
        value = arg.group(2)
        params[key] = _parse_value(value)
    return Action(kind=kind, params=params)


def _parse_value(raw: str) -> Any:
    raw = raw.strip()
    if raw.startswith('"') and raw.endswith('"'):
        return raw[1:-1]
    if raw.startswith("[") and raw.endswith("]"):
        return [int(item) for item in re.findall(r"-?\d+", raw)]
    if raw.lower() in {"true", "false"}:
        return raw.lower() == "true"
    return raw


def execute_action(
    backend: ExecutionBackend,
    action: Action,
    width: int = 1920,
    height: int = 1080,
) -> None:
    """Execute a parsed action on the selected backend."""
    kind = action.kind.lower()
    if kind == "finish":
        return
    if kind == "tap":
        element = action.params.get("element")
        if not isinstance(element, list) or len(element) < 2:
            raise RuntimeError(f"Tap 缺少坐标：{action.params}")
        x = round(int(element[0]) * width / 1000)
        y = round(int(element[1]) * height / 1000)
        backend.tap(x, y)
        return
    if kind == "launch":
        app = action.params.get("app")
        if not isinstance(app, str):
            raise RuntimeError(f"Launch 缺少应用名：{action.params}")
        backend.launch_app(app)
        return
    if kind == "swipe":
        start = action.params.get("start")
        end = action.params.get("end")
        if not isinstance(start, list) or not isinstance(end, list):
            raise RuntimeError(f"Swipe 缺少坐标：{action.params}")
        start_x = round(int(start[0]) * width / 1000)
        start_y = round(int(start[1]) * height / 1000)
        end_x = round(int(end[0]) * width / 1000)
        end_y = round(int(end[1]) * height / 1000)
        backend.swipe(start_x, start_y, end_x, end_y)
        return
    if kind == "long press":
        element = action.params.get("element")
        if not isinstance(element, list) or len(element) < 2:
            raise RuntimeError(f"Long Press 缺少坐标：{action.params}")
        x = round(int(element[0]) * width / 1000)
        y = round(int(element[1]) * height / 1000)
        backend.long_press(x, y)
        return
    if kind == "double tap":
        element = action.params.get("element")
        if not isinstance(element, list) or len(element) < 2:
            raise RuntimeError(f"Double Tap 缺少坐标：{action.params}")
        x = round(int(element[0]) * width / 1000)
        y = round(int(element[1]) * height / 1000)
        backend.double_tap(x, y)
        return
    if kind == "type":
        text = action.params.get("text")
        if not isinstance(text, str):
            raise RuntimeError(f"Type 缺少文本：{action.params}")
        backend.input_text(text)
        return
    if kind == "back":
        backend.keyevent(4)
        return
    if kind == "home":
        backend.home()
        return
    if kind == "wait":
        raw_duration = action.params.get("duration", 1)
        match = re.search(r"\d+(?:\.\d+)?", str(raw_duration))
        duration = float(match.group(0)) if match else 1.0
        time.sleep(duration)
        return
    if kind == "take_over":
        message = action.params.get("message", "需要人工接管")
        print(f"请求人工接管：{message}")
        return
    if kind in {"note", "call_api", "interact"}:
        print(f"已忽略辅助动作：{kind}")
        return
    raise RuntimeError(f"暂不支持的动作：{kind}")
