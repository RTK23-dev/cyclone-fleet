from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SIBLING = ROOT.parent / "codex-phone-mcp"
if SIBLING.is_dir() and str(SIBLING) not in sys.path:
    sys.path.insert(0, str(SIBLING))
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
