from __future__ import annotations

from dataclasses import dataclass
import os
import subprocess
from typing import Sequence


@dataclass(frozen=True)
class ADBDevice:
    serial: str
    state: str
    model: str | None = None
    product: str | None = None
    device: str | None = None
    transport_id: str | None = None


class ADBError(RuntimeError):
    pass


def _hidden_process_kwargs() -> dict[str, object]:
    """Windows-only subprocess options that keep adb and helper binaries off the desktop.

    Cyclone is a GUI product. ADB is an implementation detail and must never create a visible
    console window when the packaged Companion scans, tracks devices, forwards ports, captures
    frames, or runs bounded diagnostics.
    """
    if os.name != "nt":
        return {}
    startupinfo = subprocess.STARTUPINFO()
    startupinfo.dwFlags |= subprocess.STARTF_USESHOWWINDOW
    startupinfo.wShowWindow = subprocess.SW_HIDE
    return {
        "creationflags": subprocess.CREATE_NO_WINDOW,
        "startupinfo": startupinfo,
    }


class ADBClient:
    def __init__(self, adb_path: str = "adb", serial: str | None = None):
        self.adb_path = adb_path
        self.serial = serial

    def _base(self, use_serial: bool = True) -> list[str]:
        cmd = [self.adb_path]
        if use_serial and self.serial:
            cmd += ["-s", self.serial]
        return cmd

    def run(self, args: Sequence[str], *, binary: bool = False, timeout: float = 15, use_serial: bool = True):
        try:
            p = subprocess.run(
                self._base(use_serial) + list(args),
                capture_output=True,
                timeout=timeout,
                **_hidden_process_kwargs(),
            )
        except FileNotFoundError as exc:
            raise ADBError("ADB executable was not found. Install Android Platform Tools and add adb to PATH.") from exc
        except subprocess.TimeoutExpired as exc:
            raise ADBError("ADB command timed out") from exc
        if p.returncode:
            err = p.stderr.decode("utf-8", "replace").strip()
            raise ADBError(err or f"adb exited {p.returncode}")
        return p.stdout if binary else p.stdout.decode("utf-8", "replace")

    def start_process(self, args: Sequence[str], *, stdout=None, use_serial: bool = True):
        try:
            return subprocess.Popen(
                self._base(use_serial) + list(args),
                stdin=subprocess.DEVNULL,
                stdout=stdout or subprocess.PIPE,
                stderr=subprocess.PIPE,
                **_hidden_process_kwargs(),
            )
        except FileNotFoundError as exc:
            raise ADBError("ADB executable was not found") from exc

    def start_track_devices(self):
        """Start ADB's low-cost topology notification stream.

        The stream is used only as a wake-up signal. Device data is still read through the normal
        `adb devices -l` parser so there is one canonical inventory parser and no caller-supplied
        ADB command surface. On Windows the tracker is always created without a console window.
        """
        try:
            return subprocess.Popen(
                self._base(use_serial=False) + ["track-devices", "-l"],
                stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                bufsize=0,
                **_hidden_process_kwargs(),
            )
        except FileNotFoundError as exc:
            raise ADBError("ADB executable was not found") from exc

    def available(self) -> bool:
        try:
            self.run(["version"], timeout=5, use_serial=False)
            return True
        except ADBError:
            return False

    def connect(self, serial: str, timeout: float = 15) -> str:
        """Attach a TCP/USB serial already reachable to this ADB server.

        Used for VMOS remote-ADB bootstrap. This is transport only; it never injects input.
        """
        target = (serial or "").strip()
        if not target:
            raise ADBError("ADB connect requires a serial")
        return self.run(["connect", target], timeout=timeout, use_serial=False)

    def devices(self) -> list[ADBDevice]:
        text = self.run(["devices", "-l"], use_serial=False)
        out: list[ADBDevice] = []
        for line in text.splitlines()[1:]:
            line = line.strip()
            if not line or line.startswith("*"):
                continue
            bits = line.split()
            if len(bits) < 2:
                continue
            serial, state = bits[0], bits[1]
            props = dict(part.split(":", 1) for part in bits[2:] if ":" in part)
            out.append(ADBDevice(serial, state, props.get("model"), props.get("product"), props.get("device"), props.get("transport_id")))
        return out

    def select_device(self, requested_serial: str | None = None) -> ADBDevice:
        all_devices = self.devices()
        target = requested_serial or self.serial
        if target:
            matching = next((device for device in all_devices if device.serial == target), None)
            if matching is None:
                raise ADBError(f"Configured device {target!r} is not connected")
            if matching.state == "unauthorized":
                raise ADBError(f"Configured device {target!r} is unauthorized; unlock the phone and accept the USB debugging prompt")
            if matching.state != "device":
                raise ADBError(f"Configured device {target!r} is not ready (ADB state: {matching.state})")
            self.serial = target
            return matching

        authorized = [device for device in all_devices if device.state == "device"]
        unauthorized = [device for device in all_devices if device.state == "unauthorized"]
        if len(authorized) == 1:
            self.serial = authorized[0].serial
            return authorized[0]
        if len(authorized) > 1:
            serials = ", ".join(device.serial for device in authorized)
            raise ADBError(f"Multiple authorized ADB devices are connected ({serials}); set CYCLONE_DEVICE_SERIAL")
        if unauthorized:
            serials = ", ".join(device.serial for device in unauthorized)
            raise ADBError(f"ADB device unauthorized ({serials}); unlock the phone and accept the USB debugging prompt")
        if all_devices:
            states = ", ".join(f"{device.serial}:{device.state}" for device in all_devices)
            raise ADBError(f"No ready ADB device connected ({states})")
        raise ADBError("No ADB device connected")

    def shell(self, *args: str, timeout: float = 15) -> str:
        return self.run(["shell", *args], timeout=timeout)

    def exec_out(self, *args: str, timeout: float = 15) -> bytes:
        return self.run(["exec-out", *args], binary=True, timeout=timeout)

    def forward_mappings(self) -> list[tuple[str, str, str]]:
        text = self.run(["forward", "--list"], use_serial=False)
        mappings: list[tuple[str, str, str]] = []
        for line in text.splitlines():
            bits = line.split()
            if len(bits) >= 3:
                mappings.append((bits[0], bits[1], bits[2]))
        return mappings

    def remove_forward(self, local_port: int) -> None:
        if not self.serial:
            raise ADBError("A device serial is required to remove an isolated forward")
        self.run(["forward", "--remove", f"tcp:{local_port}"])

    def remove_stale_forward(self, local_port: int) -> None:
        """Remove a known-stale *local* forward without selecting a device.

        This is intentionally separate from :meth:`remove_forward`: fleet recovery may call it
        only after it has proved that the forward owner is no longer present in the canonical ADB
        inventory.  It is never a public API and never accepts caller supplied ADB arguments.
        """
        self.run(["forward", "--remove", f"tcp:{local_port}"], use_serial=False)

    def ensure_bridge_forward(self, local_port: int = 8766) -> bool:
        if not self.serial:
            raise ADBError("A device serial is required to create an isolated forward")
        device_serial = self.serial
        local = f"tcp:{local_port}"
        remote = "localabstract:cyclone_gateway"
        mappings = self.forward_mappings()
        for serial, existing_local, existing_remote in mappings:
            if serial == device_serial and existing_local == local and existing_remote == remote:
                return False
            if serial != device_serial and existing_local == local:
                raise ADBError("Cyclone local forward port is already owned by another device")
        if any(serial == device_serial and existing_local == local for serial, existing_local, _ in mappings):
            self.remove_forward(local_port)
        self.run(["forward", local, remote])
        return True

    def forward_bridge(self, local_port: int = 8766) -> None:
        self.ensure_bridge_forward(local_port)

    def collect_cyclone_crash_diagnostics(self) -> dict[str, str]:
        """Collect a fixed, read-only Android crash snapshot for Cyclone.

        This deliberately exposes no caller-supplied ADB or shell surface. It is used only after a
        pairing/health failure so the Windows app can preserve evidence even when the Android process
        died before its own crash journal could be read.
        """
        package = "com.cyclone.mobile"

        def capture(label: str, action) -> tuple[str, str]:
            try:
                value = str(action()).replace("\x00", "")
                return label, value[-64_000:]
            except Exception as exc:
                return label, f"<unavailable: {exc.__class__.__name__}: {str(exc)[:240]}>"

        items = [
            capture("pid", lambda: self.shell("pidof", package, timeout=4).strip()),
            capture("exit_info", lambda: self.shell("dumpsys", "activity", "exit-info", package, timeout=8)),
            capture("crash_logcat", lambda: self.run(["logcat", "-b", "crash", "-d", "-v", "threadtime", "-t", "200"], timeout=8)),
            # Beta APKs are debuggable, so run-as can retrieve Cyclone's exact local journal even
            # after the process died. Production builds still retain exit-info + crash logcat above.
            capture(
                "cyclone_process_journal",
                lambda: self.shell("run-as", package, "cat", "files/cyclone-diagnostics/process-crash-journal.log", timeout=5),
            ),
            capture(
                "cyclone_process_journal_previous",
                lambda: self.shell("run-as", package, "cat", "files/cyclone-diagnostics/process-crash-journal.log.previous", timeout=5),
            ),
            capture("enabled_accessibility_services", lambda: self.shell("settings", "get", "secure", "enabled_accessibility_services", timeout=4)),
            capture("accessibility_enabled", lambda: self.shell("settings", "get", "secure", "accessibility_enabled", timeout=4)),
        ]
        return dict(items)
