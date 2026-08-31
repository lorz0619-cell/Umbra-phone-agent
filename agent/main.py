"""MuMu phone-agent command-line entry point."""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

import imageio_ffmpeg

from .adapters.api_tasks import build_calendar_provider, build_weather_provider
from .adapters.gui_tasks import browser_search, open_web
from .actions import execute_action, parse_action
from .checks.isolation import run_isolation_check
from .config import load_project_env
from .execution import (
    AdbExecutionBackend,
    ExecutionBackend,
    VirtualDisplayBackend,
    create_backend,
)
from .llm import AutoGlmVisionClient
from .router import TaskRequest, route_command
from .utils.adb import AdbController
from .utils.scrcpy import DEFAULT_SCRCPY, VirtualDisplaySession
from .utils.ui import find_by_text, parse_ui

DEFAULT_ADB = r"D:\mumu\MuMuPlayer\nx_main\adb.exe"


def _run(args: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(args, capture_output=True, text=True)


def check_dependencies() -> int:
    """Print the versions of the key dependencies."""
    try:
        import dotenv
        import openai
        import PIL
        import requests
        import uiautomator2
    except Exception as exc:  # pragma: no cover - diagnostic path
        print(f"dependency import failed: {exc}", file=sys.stderr)
        return 1

    print("requests ok")
    print(f"python-dotenv {getattr(dotenv, '__version__', 'ok')}")
    print(f"openai {getattr(openai, '__version__', 'ok')}")
    print(f"pillow {getattr(PIL, '__version__', 'ok')}")
    print(f"uiautomator2 {getattr(uiautomator2, '__version__', 'ok')}")
    return 0


def list_devices(adb: Path) -> int:
    """List Android devices visible to ADB."""
    result = _run([str(adb), "devices"])
    if result.returncode != 0:
        print(result.stderr, file=sys.stderr)
        return result.returncode
    print(result.stdout.strip())
    return 0


def device_info(adb: Path, serial: str) -> int:
    """Print model and Android version for one device."""
    model = _run([str(adb), "-s", serial, "shell", "getprop", "ro.product.model"])
    version = _run(
        [str(adb), "-s", serial, "shell", "getprop", "ro.build.version.release"]
    )
    print(f"serial: {serial}")
    print(f"model: {model.stdout.strip()}")
    print(f"android: {version.stdout.strip()}")
    return 0


def screenshot(
    adb: Path,
    serial: str,
    output: Path,
    display_id: int | None = None,
) -> int:
    """Capture a screenshot using ADB and save it locally."""
    try:
        AdbController(adb, serial).screencap(output, display_id=display_id)
    except Exception as exc:
        print(exc, file=sys.stderr)
        return 1
    print(f"screenshot saved to {output}")
    return 0


def extract_video_frame(video: Path, at: float, output: Path) -> int:
    """Extract one PNG frame from an MP4 recording for visual inspection."""
    try:
        ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
        subprocess.run(
            [
                ffmpeg,
                "-y",
                "-ss",
                str(at),
                "-i",
                str(video),
                "-frames:v",
                "1",
                str(output),
            ],
            check=True,
            capture_output=True,
        )
    except subprocess.CalledProcessError as exc:
        print(exc.stderr.decode(errors="ignore"), file=sys.stderr)
        return 1
    print(f"frame saved to {output}")
    return 0


def run_task(
    request: TaskRequest | None,
    weather_provider: str,
    calendar_provider: str,
    backend: ExecutionBackend,
) -> int:
    """Execute a routed task and print a user-facing result."""
    if request is None:
        print("暂不支持该命令")
        return 1

    if request.task_type == "weather":
        try:
            city = str(request.params.get("city") or "北京")
            provider = build_weather_provider(weather_provider)
            result = provider.get_weather(city)
        except Exception as exc:
            print(f"任务失败：{exc}", file=sys.stderr)
            return 1
        print(result.as_text())
        return 0

    if request.task_type == "calendar":
        try:
            provider = build_calendar_provider(calendar_provider)
            result = provider.add_event(
                event_date=str(request.params["date"]),
                event_time=str(request.params["time"]),
                title=str(request.params["title"]),
            )
        except Exception as exc:
            print(f"任务失败：{exc}", file=sys.stderr)
            return 1
        print(result.as_text())
        return 0

    if request.task_type == "gui_open_web":
        try:
            result = open_web(
                backend,
                str(request.params["url"]),
                Path("artifacts/gui.png"),
            )
        except Exception as exc:
            print(f"任务失败：{exc}", file=sys.stderr)
            return 1
        print(result.as_text())
        return 0

    if request.task_type == "gui_search":
        try:
            result = browser_search(
                backend,
                str(request.params["query"]),
                Path("artifacts/gui_search.png"),
            )
        except Exception as exc:
            print(f"任务失败：{exc}", file=sys.stderr)
            return 1
        print(result.as_text())
        return 0

    print(f"未实现的任务类型：{request.task_type}", file=sys.stderr)
    return 1


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default=DEFAULT_ADB, help="Path to adb.exe")
    parser.add_argument(
        "--scrcpy",
        default=str(DEFAULT_SCRCPY),
        help="Path to scrcpy.exe",
    )
    parser.add_argument("--serial", default="127.0.0.1:16384", help="ADB device serial")
    sub = parser.add_subparsers(dest="subcommand")
    sub.add_parser("check")
    sub.add_parser("devices")
    sub.add_parser("info")
    sub.add_parser("displays")
    shot = sub.add_parser("screenshot")
    shot.add_argument("--output", default="artifacts/screen.png")
    shot.add_argument("--display", type=int, help="Display id to capture")
    run = sub.add_parser("run")
    run.add_argument("--command", required=True, help="Natural-language command")
    run.add_argument(
        "--weather-provider",
        choices=["mock", "open-meteo"],
        default="mock",
        help="Weather backend used for the smoke test",
    )
    run.add_argument(
        "--calendar-provider",
        choices=["local-json"],
        default="local-json",
        help="Calendar backend used for local development",
    )
    run.add_argument("--display", type=int, help="Display id for GUI execution")
    tap_parser = sub.add_parser("tap")
    tap_parser.add_argument("--x", type=int, required=True)
    tap_parser.add_argument("--y", type=int, required=True)
    text_parser = sub.add_parser("text")
    text_parser.add_argument("--text", required=True)
    ui_parser = sub.add_parser("ui")
    ui_parser.add_argument("--output", default="artifacts/ui.xml")
    ui_parser.add_argument("--find", help="Find UI elements containing this text")
    search_parser = sub.add_parser("search")
    search_parser.add_argument("--query", required=True)
    virtual_parser = sub.add_parser("virtual")
    virtual_parser.add_argument("--app", help="App package/activity to start")
    virtual_parser.add_argument("--size", default="1920x1080")
    virtual_parser.add_argument("--time-limit", type=int, default=10)
    virtual_parser.add_argument("--record", help="Optional mp4 output path")
    virtual_run_parser = sub.add_parser("virtual-run")
    virtual_run_parser.add_argument("--command", required=True)
    virtual_run_parser.add_argument("--app", default="com.android.chromium")
    virtual_run_parser.add_argument("--size", default="1920x1080")
    virtual_run_parser.add_argument("--record", help="Optional mp4 output path")
    virtual_run_parser.add_argument("--time-limit", type=int)
    isolation_parser = sub.add_parser("isolation-check")
    isolation_parser.add_argument("--app", default="com.android.chromium")
    isolation_parser.add_argument("--record", help="Optional mp4 output path")
    isolation_parser.add_argument("--output-dir", default="artifacts/isolation")
    vlm_parser = sub.add_parser("vlm")
    vlm_parser.add_argument(
        "--source",
        choices=["main", "virtual", "screenshot"],
        default="screenshot",
        help="Which screen to capture before asking AutoGLM",
    )
    vlm_parser.add_argument("--screenshot", default="artifacts/gui_search.png")
    vlm_parser.add_argument("--command", required=True)
    vlm_parser.add_argument("--app", default="com.android.chromium")
    vlm_parser.add_argument("--record", help="Optional mp4 output path")
    vlm_run_parser = sub.add_parser("vlm-run")
    vlm_run_parser.add_argument("--command", required=True)
    vlm_run_parser.add_argument("--url", help="Initial URL to open before the loop")
    vlm_run_parser.add_argument("--app", default="com.android.chromium")
    vlm_run_parser.add_argument("--record", help="Optional mp4 output path")
    vlm_run_parser.add_argument("--max-steps", type=int, default=5)
    frame_parser = sub.add_parser("frame")
    frame_parser.add_argument("--video", required=True)
    frame_parser.add_argument("--at", type=float, default=1.0)
    frame_parser.add_argument("--output", default="artifacts/frame.png")
    return parser


def main() -> int:
    load_project_env()
    parser = build_parser()
    args = parser.parse_args()
    adb = Path(args.adb)

    if args.subcommand == "check":
        return check_dependencies()
    if args.subcommand == "devices":
        return list_devices(adb)
    if args.subcommand == "info":
        return device_info(adb, args.serial)
    if args.subcommand == "displays":
        try:
            ids = AdbController(adb, args.serial).list_display_ids()
        except Exception as exc:
            print(exc, file=sys.stderr)
            return 1
        print("display ids:", ", ".join(str(item) for item in ids))
        return 0
    if args.subcommand == "screenshot":
        return screenshot(adb, args.serial, Path(args.output), args.display)
    if args.subcommand == "run":
        request = route_command(args.command)
        backend = AdbExecutionBackend(
            AdbController(adb, args.serial),
            display_id=args.display,
        )
        return run_task(
            request,
            args.weather_provider,
            args.calendar_provider,
            backend,
        )
    if args.subcommand == "tap":
        try:
            AdbController(adb, args.serial).tap(args.x, args.y)
        except Exception as exc:
            print(exc, file=sys.stderr)
            return 1
        print("tap ok")
        return 0
    if args.subcommand == "text":
        try:
            AdbController(adb, args.serial).input_text(args.text)
        except Exception as exc:
            print(exc, file=sys.stderr)
            return 1
        print("text ok")
        return 0
    if args.subcommand == "ui":
        try:
            output = AdbController(adb, args.serial).dump_ui(Path(args.output))
        except Exception as exc:
            print(exc, file=sys.stderr)
            return 1
        print(f"ui saved to {args.output}")
        if args.find:
            elements = parse_ui(output)
            matches = find_by_text(elements, args.find)
            if not matches:
                print(f"未找到包含“{args.find}”的控件")
                return 0
            for item in matches[:10]:
                x, y = item.center
                label = item.text or item.content_desc
                print(f"{label} -> center=({x},{y}) bounds={item.bounds}")
        return 0
    if args.subcommand == "search":
        request = TaskRequest(task_type="gui_search", params={"query": args.query})
        backend = AdbExecutionBackend(AdbController(adb, args.serial))
        return run_task(
            request,
            "mock",
            "local-json",
            backend,
        )
    if args.subcommand == "virtual":
        try:
            session = VirtualDisplaySession(
                Path(args.scrcpy),
                args.serial,
                app=args.app,
                size=args.size,
                record=args.record,
                time_limit=args.time_limit,
            )
            display_id = session.start()
        except Exception as exc:
            print(exc, file=sys.stderr)
            return 1
        print(f"virtual display id: {display_id}")
        exit_code = session.wait()
        if exit_code != 0:
            print(f"scrcpy exited with code {exit_code}", file=sys.stderr)
            return exit_code
        print("virtual display stopped")
        return 0
    if args.subcommand == "virtual-run":
        backend = VirtualDisplayBackend(
            AdbController(adb, args.serial),
            Path(args.scrcpy),
            args.serial,
            app=args.app,
            size=args.size,
            record=args.record,
            time_limit=args.time_limit,
        )
        try:
            backend.__enter__()
            request = route_command(args.command)
            exit_code = run_task(request, "mock", "local-json", backend)
        except Exception as exc:
            print(exc, file=sys.stderr)
            exit_code = 1
        finally:
            backend.__exit__(None, None, None)
        return exit_code
    if args.subcommand == "isolation-check":
        try:
            unchanged = run_isolation_check(
                adb,
                Path(args.scrcpy),
                args.serial,
                Path(args.output_dir),
                app=args.app,
                record=args.record,
            )
        except Exception as exc:
            print(exc, file=sys.stderr)
            return 1
        return 0 if unchanged else 1
    if args.subcommand == "vlm":
        try:
            controller = AdbController(adb, args.serial)
            source_path = Path(args.screenshot)
            backend_ctx = None
            if args.source == "main":
                source_path = Path("artifacts/vlm_main.png")
                create_backend("physical", controller).screencap(source_path)
            elif args.source == "virtual":
                source_path = Path("artifacts/vlm_virtual.png")
                backend_ctx = create_backend(
                    "virtual",
                    controller,
                    Path(args.scrcpy),
                    args.serial,
                    app=args.app,
                    record=args.record,
                )
                backend_ctx.__enter__()
                backend_ctx.screencap(source_path)

            client = AutoGlmVisionClient()
            result = client.analyze_screenshot(source_path, args.command)
        except Exception as exc:
            print(exc, file=sys.stderr)
            return 1
        finally:
            if backend_ctx is not None:
                backend_ctx.__exit__(None, None, None)
        print(result)
        return 0
    if args.subcommand == "vlm-run":
        backend = VirtualDisplayBackend(
            AdbController(adb, args.serial),
            Path(args.scrcpy),
            args.serial,
            app=args.app,
            record=args.record,
        )
        try:
            backend.__enter__()
            if args.url:
                backend.open_url(args.url)
            client = AutoGlmVisionClient()
            for step in range(args.max_steps):
                shot = Path(f"artifacts/vlm_step_{step + 1}.png")
                backend.screencap(shot)
                text = client.analyze_screenshot(shot, args.command)
                print(f"--- step {step + 1} ---")
                print(text)
                action = parse_action(text)
                if action is None:
                    print("未解析到动作，停止")
                    break
                if action.kind == "finish":
                    print(f"任务完成：{action.message}")
                    break
                execute_action(backend, action)
        except Exception as exc:
            print(exc, file=sys.stderr)
            return 1
        finally:
            backend.__exit__(None, None, None)
        return 0
    if args.subcommand == "frame":
        return extract_video_frame(
            Path(args.video),
            args.at,
            Path(args.output),
        )

    parser.print_help()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
