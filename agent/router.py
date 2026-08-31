"""Turn a natural-language command into a structured task request."""

from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import date, timedelta
from typing import Any


_SITE_SHORTCUTS = {
    "百度": "https://www.baidu.com",
    "必应": "https://www.bing.com",
    "哔哩哔哩": "https://www.bilibili.com",
    "bilibili": "https://www.bilibili.com",
    "淘宝": "https://www.taobao.com",
    "京东": "https://www.jd.com",
    "微博": "https://m.weibo.cn",
}


@dataclass(frozen=True)
class TaskRequest:
    """A task selected by the command router."""

    task_type: str
    params: dict[str, Any]


_CITY_PATTERN = re.compile(r"(?P<city>[\u4e00-\u9fa5A-Za-z]{2,20})")


def _extract_city(text: str) -> str | None:
    cleaned = re.sub(r"天气|查询|查一下|帮我|看看|weather|in|的", " ", text, flags=re.I)
    match = _CITY_PATTERN.search(cleaned)
    if not match:
        return None
    city = match.group("city").strip()
    return city or None


def _parse_calendar(text: str) -> dict[str, Any]:
    """Parse a small, predictable calendar-command grammar.

    Supported examples:
      - 明天下午3点 项目评审
      - 添加日历 2026-08-27 15:00 项目评审
      - 帮我安排后天上午9点 牙医
    """
    day_offset = 1
    if "后天" in text:
        day_offset = 2
    elif "明天" in text:
        day_offset = 1
    elif "今天" in text:
        day_offset = 0

    event_date = date.today() + timedelta(days=day_offset)
    event_time = "09:00"

    clock_match = re.search(r"(\d{1,2})[:：](\d{2})", text)
    point_match = re.search(r"(上午|下午|晚上)?(\d{1,2})点", text)
    if clock_match:
        event_time = f"{int(clock_match.group(1)):02d}:{clock_match.group(2)}"
    elif point_match:
        hour = int(point_match.group(2))
        marker = point_match.group(1)
        if marker == "下午" and hour < 12:
            hour += 12
        if marker == "晚上" and hour < 12:
            hour += 12
        event_time = f"{hour:02d}:00"

    title = re.sub(
        r"添加|帮我|日历|日程|安排|创建|今天|明天|后天|"
        r"\d{1,2}[:：]\d{2}|上午|下午|晚上|\d{1,2}点",
        " ",
        text,
    )
    title = re.sub(r"\s+", " ", title).strip(" ，,。")
    title = title or "未命名日程"

    return {
        "date": event_date.isoformat(),
        "time": event_time,
        "title": title,
    }


def _extract_url(text: str) -> str | None:
    direct = re.search(r"https?://[^\s]+", text, flags=re.I)
    if direct:
        return direct.group(0)
    for name, url in _SITE_SHORTCUTS.items():
        if name in text:
            return url
    return None


def _extract_search_query(text: str) -> str | None:
    cleaned = re.sub(
        r"搜索|搜一下|帮我搜|查询|查一下|百度|谷歌|必应",
        " ",
        text,
        flags=re.I,
    )
    query = re.sub(r"\s+", " ", cleaned).strip(" ，,。")
    return query or None


def route_command(text: str) -> TaskRequest | None:
    """Route a command to a task adapter.

    The first version intentionally uses simple rules. It keeps the demo
    deterministic and gives us a clean place to swap in an LLM later.
    """
    normalized = text.strip()
    if not normalized:
        return None

    lowered = normalized.lower()
    if "天气" in normalized or "weather" in lowered:
        city = _extract_city(normalized)
        return TaskRequest(task_type="weather", params={"city": city})

    has_calendar_word = any(
        word in normalized for word in ("日历", "日程", "会议", "安排")
    )
    has_date_and_time = (
        any(word in normalized for word in ("今天", "明天", "后天"))
        and re.search(r"\d{1,2}(?:[:：点])", normalized) is not None
    )
    if has_calendar_word or has_date_and_time:
        return TaskRequest(task_type="calendar", params=_parse_calendar(normalized))

    if any(word in normalized for word in ("搜索", "搜一下", "帮我搜", "查询")):
        query = _extract_search_query(normalized)
        if query:
            return TaskRequest(task_type="gui_search", params={"query": query})

    if "打开" in normalized:
        url = _extract_url(normalized)
        if url:
            return TaskRequest(task_type="gui_open_web", params={"url": url})

    return None
