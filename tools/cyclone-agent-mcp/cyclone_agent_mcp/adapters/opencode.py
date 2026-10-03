from __future__ import annotations

from pathlib import Path

from ._base import HostAdapter


class OpenCodeAdapter(HostAdapter):
    id = "opencode"
    title = "OpenCode"

    def _config_path(self) -> Path:
        from .. import status as status_mod

        return status_mod.opencode_config_path()

    def _configured(self) -> bool:
        from ..status import _json_opencode_configured

        return _json_opencode_configured(self._config_path())
