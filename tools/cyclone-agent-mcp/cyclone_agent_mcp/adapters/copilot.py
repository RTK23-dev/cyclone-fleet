from __future__ import annotations

from pathlib import Path

from ._base import HostAdapter


class CopilotAdapter(HostAdapter):
    id = "copilot"
    title = "Copilot"

    def _config_path(self) -> Path:
        from .. import status as status_mod

        return status_mod.copilot_config_path()

    def _configured(self) -> bool:
        from ..status import _json_copilot_configured

        return _json_copilot_configured(self._config_path())
