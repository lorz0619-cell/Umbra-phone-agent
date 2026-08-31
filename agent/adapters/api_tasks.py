"""API-backed tasks that do not need the phone screen."""

from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import date
from pathlib import Path
from typing import Protocol

import requests

from ..config import PROJECT_ROOT


@dataclass(frozen=True)
class WeatherResult:
    city: str
    temperature_c: float
    description: str

    def as_text(self) -> str:
        return (
            f"{self.city} 当前天气：{self.description}，"
            f"气温 {self.temperature_c:.1f}°C"
        )


@dataclass(frozen=True)
class CalendarResult:
    event_id: str
    event_date: str
    event_time: str
    title: str

    def as_text(self) -> str:
        return f"已添加日程：{self.event_date} {self.event_time} {self.title}"


class WeatherProvider(Protocol):
    def get_weather(self, city: str) -> WeatherResult:
        """Return current weather for a city."""


class MockWeatherProvider:
    """Deterministic provider used for offline smoke tests."""

    def get_weather(self, city: str) -> WeatherResult:
        return WeatherResult(city=city, temperature_c=23.5, description="晴")


class CalendarProvider(Protocol):
    def add_event(self, event_date: str, event_time: str, title: str) -> CalendarResult:
        """Store an event and return a result."""


class LocalJsonCalendarProvider:
    """File-backed calendar used for local development and demos."""

    def __init__(self, path: Path | None = None) -> None:
        self.path = path or PROJECT_ROOT / "artifacts" / "calendar.json"

    def add_event(self, event_date: str, event_time: str, title: str) -> CalendarResult:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        events = self._read()
        event_id = f"evt-{len(events) + 1:03d}"
        events.append(
            {
                "id": event_id,
                "date": event_date,
                "time": event_time,
                "title": title,
            }
        )
        self._write(events)
        return CalendarResult(event_id, event_date, event_time, title)

    def _read(self) -> list[dict[str, str]]:
        if not self.path.exists():
            return []
        try:
            data = json.loads(self.path.read_text(encoding="utf-8"))
            return data if isinstance(data, list) else []
        except (OSError, json.JSONDecodeError):
            return []

    def _write(self, events: list[dict[str, str]]) -> None:
        self.path.write_text(
            json.dumps(events, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )


class OpenMeteoWeatherProvider:
    """Provider backed by the keyless Open-Meteo API."""

    geocoding_url = "https://geocoding-api.open-meteo.com/v1/search"
    forecast_url = "https://api.open-meteo.com/v1/forecast"

    def get_weather(self, city: str) -> WeatherResult:
        if not city:
            raise ValueError("缺少城市名")

        geo = requests.get(
            self.geocoding_url,
            params={"name": city, "count": 1, "language": "zh"},
            timeout=10,
        )
        geo.raise_for_status()
        results = geo.json().get("results") or []
        if not results:
            raise ValueError(f"找不到城市：{city}")

        first = results[0]
        latitude = first["latitude"]
        longitude = first["longitude"]
        name = first.get("name") or city

        weather = requests.get(
            self.forecast_url,
            params={
                "latitude": latitude,
                "longitude": longitude,
                "current": "temperature_2m,weather_code",
            },
            timeout=10,
        )
        weather.raise_for_status()
        current = weather.json()["current"]
        code = int(current["weather_code"])
        return WeatherResult(
            city=name,
            temperature_c=float(current["temperature_2m"]),
            description=_describe_code(code),
        )


def _describe_code(code: int) -> str:
    if code == 0:
        return "晴"
    if code in {1, 2, 3}:
        return "多云"
    if code in {45, 48}:
        return "雾"
    if code in {51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82}:
        return "有雨"
    if code in {71, 73, 75, 77, 85, 86}:
        return "有雪"
    if code in {95, 96, 99}:
        return "雷雨"
    return "未知"


def build_weather_provider(name: str) -> WeatherProvider:
    """Build a weather provider by name."""
    if name == "open-meteo":
        return OpenMeteoWeatherProvider()
    if name == "mock":
        return MockWeatherProvider()
    raise ValueError(f"未知天气提供者：{name}")


def build_calendar_provider(name: str) -> CalendarProvider:
    """Build a calendar provider by name."""
    if name == "local-json":
        return LocalJsonCalendarProvider()
    raise ValueError(f"未知日历提供者：{name}")
