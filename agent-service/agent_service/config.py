from dataclasses import dataclass, field
from pathlib import Path
import os

from .runtime.policy import Limits


@dataclass
class Config:
    directory: Path = field(default_factory=lambda: Path(os.environ.get("AGENT_DATA_DIRECTORY", ".local")))
    limits: Limits = field(default_factory=Limits)
    scenario: str = "normal"
    trace: bool = True


def load_env(path):
    """Load explicit local configuration without printing secrets or overwriting env."""
    from dotenv import load_dotenv
    load_dotenv(path, override=False)
