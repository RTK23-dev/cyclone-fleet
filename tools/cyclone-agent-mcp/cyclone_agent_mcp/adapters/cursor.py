from __future__ import annotations

from pathlib import Path

from ._base import HostAdapter


class CursorAdapter(HostAdapter):
    id = "cursor"
    title = "Cursor"

    def _config_path(self) -> Path:
        from .. import status as status_mod

        return status_mod.cursor_mcp_path()

    def _configured(self) -> bool:
        from ..status import _json_mcp_servers_configured

        return _json_mcp_servers_configured(self._config_path())
