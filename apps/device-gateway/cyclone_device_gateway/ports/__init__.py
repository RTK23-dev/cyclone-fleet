"""The Port Hub (plan 48): Cyclone Ports in the gateway. The contract and its catalog, signing, validators and checker
come from the kit in ``tools/cyclone-ports-sdk`` (``cyclone_ports``), never a copy: the PC package installs the kit, and
a source checkout finds it next to the gateway.
"""
from __future__ import annotations

import sys
from pathlib import Path


def _load_kit():
    try:
        import cyclone_ports  # noqa: F401
        return cyclone_ports
    except ImportError:
        kit = Path(__file__).resolve().parents[4] / "tools" / "cyclone-ports-sdk"
        if (kit / "cyclone_ports" / "__init__.py").is_file():
            sys.path.append(str(kit))
            import cyclone_ports
            return cyclone_ports
        return None


kit = _load_kit()
AVAILABLE = kit is not None
