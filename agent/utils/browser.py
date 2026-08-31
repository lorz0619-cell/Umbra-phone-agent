"""Browser resolution and launch helpers for Android execution backends."""

from __future__ import annotations

from .adb import AdbController


class BrowserProvider:
    """Resolve a browser component and open URLs without hard-coding a package."""

    CHROMIUM_PACKAGES = (
        "com.android.chromium",
        "com.android.chrome",
        "com.microsoft.emmx",
        "com.opera.browser",
        "com.sec.android.app.sbrowser",
        "com.huawei.browser",
        "com.ucmobile",
        "com.UCMobile",
        "com.tencent.mtt",
    )

    CANDIDATES = (
        "com.android.chromium",
        "com.android.chrome",
        "com.microsoft.emmx",
        "com.opera.browser",
        "com.sec.android.app.sbrowser",
        "com.huawei.browser",
        "com.ucmobile",
        "com.UCMobile",
        "com.tencent.mtt",
        "org.mozilla.firefox",
    )

    def __init__(self, controller: AdbController) -> None:
        self.controller = controller

    def resolve(self, url: str) -> str | None:
        """Return the default browser component for a URL, if resolvable."""
        result = self.controller.shell(
            "cmd",
            "package",
            "resolve-activity",
            "--brief",
            "-a",
            "android.intent.action.VIEW",
            "-d",
            url,
        )
        if result.returncode != 0:
            return None
        lines = [line.strip() for line in result.stdout.splitlines() if line.strip()]
        for line in reversed(lines):
            if "/" in line and not line.startswith("priority"):
                return line
        return None

    def fallback_candidates(self) -> list[str]:
        """Return installed browser candidates from a small allowlist."""
        installed = self._installed_packages()
        return [
            package
            for package in self.CANDIDATES
            if package in installed
        ]

    def prefer_chromium(self, url: str) -> str | None:
        """Return the default browser if it is Chromium, otherwise an installed one."""
        component = self.resolve(url)
        if component and self._is_chromium(component):
            return component

        installed = self._installed_packages()
        for package in self.CHROMIUM_PACKAGES:
            if package not in installed:
                continue
            candidate = self._resolve_package(package)
            if candidate:
                return candidate
        return component

    def open_url(self, url: str, display_id: int | None = None) -> str | None:
        """Open a URL on the selected display, preferring a Chromium browser."""
        component = self.prefer_chromium(url)
        args = ["shell", "am", "start"]
        if display_id is not None:
            args.extend(["--display", str(display_id)])
        args.extend(["-a", "android.intent.action.VIEW", "-d", url])
        if component:
            args.extend(["-n", component])
        result = self.controller.run(*args)
        if result.returncode != 0:
            raise RuntimeError(result.stderr.strip())
        return component

    def webview_fallback(self, url: str, display_id: int | None = None) -> str | None:
        """Fall back to a generic VIEW intent when no explicit component resolves."""
        return self.open_url(url, display_id=display_id)

    def _installed_packages(self) -> set[str]:
        result = self.controller.shell("pm", "list", "packages")
        return {line.strip().removeprefix("package:") for line in result.stdout.splitlines()}

    def _resolve_package(self, package: str) -> str | None:
        result = self.controller.shell(
            "cmd",
            "package",
            "resolve-activity",
            "--brief",
            package,
        )
        if result.returncode != 0:
            return None
        lines = [line.strip() for line in result.stdout.splitlines() if line.strip()]
        for line in reversed(lines):
            if "/" in line and not line.startswith("priority"):
                return line
        return None

    def _is_chromium(self, component: str) -> bool:
        package = component.split("/", 1)[0]
        return package in self.CHROMIUM_PACKAGES
