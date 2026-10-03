from __future__ import annotations

from pathlib import Path

from ._base import HostAdapter


class CodexAdapter(HostAdapter):
    id = "codex"
    title = "Codex"

    def _config_path(self) -> Path:
        from .. import status as status_mod

        return status_mod.codex_config_path()

    def _configured(self) -> bool:
        from ..status import _codex_configured

        return _codex_configured(self._config_path())
