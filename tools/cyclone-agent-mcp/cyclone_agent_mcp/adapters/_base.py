from __future__ import annotations

from pathlib import Path
from typing import Any, Callable

from .local_ai_adapter import ConnectionStatus, DetectionResult, RepairResult, ai_state


class HostAdapter:
    id: str
    title: str

    def _installed(self) -> bool:
        from .. import status as status_mod

        return status_mod.host_installed(self.id)

    def _config_path(self) -> Path | None:
        return None

    def _configured(self) -> bool:
        return False

    def _server_ready(self) -> bool:
        from ..status import _command_exists
        from ..connector import resolve_server_command

        return _command_exists(resolve_server_command().command)

    def detect(self) -> DetectionResult:
        installed = self._installed()
        path = self._config_path()
        return DetectionResult(
            id=self.id,
            detected=installed,
            installed=installed,
            config_path=str(path) if path is not None else None,
            detail=f"{self.title} {'detected' if installed else 'not installed'} on this PC",
        )

    def status(self) -> ConnectionStatus:
        installed = self._installed()
        try:
            configured = self._configured()
            failed = False
        except Exception:
            configured = False
            failed = True
        server_ready = self._server_ready()
        path = self._config_path()
        state = ai_state(installed=installed, configured=configured, server_ready=server_ready, failed=failed)
        return ConnectionStatus(
            id=self.id,
            state=state,
            detected=installed,
            configured=configured,
            server_ready=server_ready,
            detail=_status_detail(self.title, state),
            config_path=str(path) if path is not None else None,
        )

    def connect(self, *, dry_run: bool = False, executable: str | None = None) -> dict[str, Any]:
        from ..connector import apply_connect

        return apply_connect(self.id, dry_run=dry_run, executable=executable)

    def disconnect(self, *, dry_run: bool = False) -> dict[str, Any]:
        from ..connector import apply_disconnect

        return apply_disconnect(self.id, dry_run=dry_run)

    def verify(self, *, executable: str | None = None) -> dict[str, Any]:
        from ..connector import verify_tools_list

        return verify_tools_list(executable)

    def repair(self, *, dry_run: bool = False, executable: str | None = None) -> RepairResult:
        current = self.status()
        if current.state == "UNKNOWN":
            return RepairResult(
                id=self.id,
                layer="local_ai",
                action="install",
                label="Install",
                detail=f"{self.title} is not installed on this PC",
                ok=False,
            )
        result = self.connect(dry_run=dry_run, executable=executable)
        return RepairResult(
            id=self.id,
            layer="local_ai",
            action="configure",
            label="Configure",
            detail=str(result.get("path") or f"{self.title} configuration updated"),
            ok=True,
        )


def json_configured(path: Path, reader: Callable[[Path], bool]) -> bool:
    if not path.exists():
        return False
    return bool(reader(path))


def _status_detail(title: str, state: str) -> str:
    return {
        "UNKNOWN": f"{title} not installed",
        "DETECTED": f"{title} available",
        "CONFIGURED": f"{title} configured",
        "CONNECTED": f"{title} connected",
        "FAILED": f"{title} needs configuration",
    }.get(state, title)
