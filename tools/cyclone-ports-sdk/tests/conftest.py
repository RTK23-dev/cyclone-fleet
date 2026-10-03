import importlib.util
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))


def load_example(name: str):
    spec = importlib.util.spec_from_file_location(f"example_{name}", ROOT / "examples" / name / "plugin.py")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


@pytest.fixture
def examples():
    return {name: load_example(name) for name in ("logger", "pc_images", "sms_plugin")}
