"""Small configuration helpers loaded from the environment."""

from __future__ import annotations

import os
from pathlib import Path

from dotenv import load_dotenv

PROJECT_ROOT = Path(__file__).resolve().parents[1]


def load_project_env() -> None:
    """Load .env from the repository root if present."""
    load_dotenv(PROJECT_ROOT / ".env")


def get_env(name: str, default: str = "") -> str:
    """Return an environment variable, falling back to a default."""
    return os.getenv(name, default)
