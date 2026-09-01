"""Provider-neutral OpenAI-compatible vision-language model client."""

from __future__ import annotations

import base64
from datetime import datetime
from pathlib import Path

from openai import OpenAI

from .config import get_env


class GenericVisionClient:
    """Thin client for a configurable multimodal chat-completions model."""

    def __init__(
        self,
        api_key: str | None = None,
        base_url: str | None = None,
        model: str | None = None,
    ) -> None:
        self.api_key = api_key or get_env("VLM_API_KEY")
        self.base_url = base_url or get_env(
            "VLM_BASE_URL",
            "https://api.openai.com/v1",
        )
        self.model = model or get_env("VLM_MODEL", "gpt-4.1-mini")
        if not self.api_key:
            raise RuntimeError("缺少 VLM_API_KEY，请在 .env 中配置")
        self.client = OpenAI(api_key=self.api_key, base_url=self.base_url)

    def analyze_screenshot(self, screenshot: Path, task: str) -> str:
        """Return the model's next action for a screenshot and task."""
        data_url = self._data_url(screenshot)
        response = self.client.chat.completions.create(
            model=self.model,
            temperature=0,
            messages=[
                {
                    "role": "system",
                    "content": self._system_prompt(),
                },
                {
                    "role": "user",
                    "content": [
                        {
                            "type": "image_url",
                            "image_url": {"url": data_url},
                        },
                        {"type": "text", "text": task},
                    ],
                },
            ],
        )
        if not response.choices:
            raise RuntimeError("模型没有返回结果")
        return response.choices[0].message.content or ""

    def _system_prompt(self) -> str:
        today = datetime.today()
        return (
            f"今天的日期是: {today.strftime('%Y年%m月%d日')}\n"
            "你是一个智能体分析专家，可以根据操作历史和当前状态图执行一系列操作来完成任务。\n"
            "你必须严格按照要求输出以下格式：\n"
            "{think}\n"
            "{action}\n"
            "其中 {think} 是对你为什么选择这个操作的简短推理说明，"
            "{action} 是本次执行的具体操作指令。\n"
            '可用操作包括：do(action="Launch", app="xxx")、'
            'do(action="Tap", element=[x,y])、'
            'do(action="Type", text="xxx")、'
            'do(action="Swipe", start=[x1,y1], end=[x2,y2])、'
            'do(action="Back")、'
            'do(action="Wait", duration="x seconds")、'
            'do(action="Take_over", message="xxx")、'
            'finish(message="xxx")。\n'
            "坐标系统从左上角 (0,0) 到右下角 (999,999)。\n"
            "规则：只输出下一步；先检查当前页面是否加载完成，"
            "未加载完成则 Wait；如果页面无关则 Back；"
            "连续 Wait 不超过三次；结束前检查任务是否完成。"
        )

    def _data_url(self, screenshot: Path) -> str:
        encoded = base64.b64encode(screenshot.read_bytes()).decode("ascii")
        return f"data:image/png;base64,{encoded}"
