from __future__ import annotations

from pathlib import Path

from ._base import HostAdapter
from .local_ai_adapter import DetectionResult


class GenericMcpAdapter(HostAdapter):
    id = "generic"
    title = "Generic MCP"

    def _installed(self) -> bool:
        return True

    def _config_path(self) -> Path | None:
        return None

    def _configured(self) -> bool:
        return self._server_ready()

    def detect(self) -> DetectionResult:
        return DetectionResult(
            id=self.id,
            detected=True,
            installed=True,
            config_path=None,
            detail="Compatible local MCP clients can use Cyclone's typed stdio transport",
        )
