from __future__ import annotations

from dataclasses import dataclass
import os
from pathlib import Path
import shutil
import subprocess
import time
from typing import Any, Protocol, Sequence

from .models import VirtualDeviceConfig, VirtualInstance, VirtualInstanceState, VirtualProviderHealth


@dataclass(frozen=True)
class CommandResult:
    returncode: int
    stdout: str = ""
    stderr: str = ""


class AllowlistedRunner(Protocol):
    def run(self, executable: Path, args: Sequence[str], *, timeout: float = 30, input_text: str | None = None, env: dict[str, str] | None = None) -> CommandResult: ...
    def start(self, executable: Path, args: Sequence[str], *, env: dict[str, str] | None = None) -> Any: ...


class SubprocessAllowlistedRunner:
    def run(self, executable: Path, args: Sequence[str], *, timeout: float = 30, input_text: str | None = None, env: dict[str, str] | None = None) -> CommandResult:
        completed = subprocess.run(
            [str(executable), *map(str, args)], input=input_text, text=True, capture_output=True,
            timeout=timeout, env=env, shell=False,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0,
        )
        return CommandResult(completed.returncode, completed.stdout, completed.stderr)

    def start(self, executable: Path, args: Sequence[str], *, env: dict[str, str] | None = None) -> Any:
        return subprocess.Popen(
            [str(executable), *map(str, args)], stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, env=env, shell=False,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0,
        )


def resolve_android_sdk() -> Path | None:
    candidates: list[Path] = []
    for key in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        if os.getenv(key):
            candidates.append(Path(os.environ[key]))
    if os.getenv("LOCALAPPDATA"):
        local = Path(os.environ["LOCALAPPDATA"])
        candidates.extend([local / "Android" / "Sdk", local / "Android"])
    return next((item for item in candidates if (item / "emulator" / "emulator.exe").is_file()), None)


class AndroidEmulatorProvider:
    provider_id = "android-emulator"

    def __init__(self, runtime_dir: Path, *, sdk_root: Path | None = None, runner: AllowlistedRunner | None = None, boot_timeout: float = 90):
        self.runtime_dir = runtime_dir.resolve()
        self.sdk_root = (sdk_root or resolve_android_sdk())
        self.runner = runner or SubprocessAllowlistedRunner()
        self.boot_timeout = max(5.0, min(float(boot_timeout), 300.0))
        self.avd_home = self.runtime_dir / "virtual" / "avd"

    @property
    def emulator(self) -> Path | None:
        if not self.sdk_root:
            return None
        name = "emulator.exe" if os.name == "nt" else "emulator"
        return self.sdk_root / "emulator" / name

    @property
    def avdmanager(self) -> Path | None:
        if not self.sdk_root:
            return None
        name = "avdmanager.bat" if os.name == "nt" else "avdmanager"
        candidates = list((self.sdk_root / "cmdline-tools").glob(f"*/bin/{name}"))
        return next((item for item in sorted(candidates, reverse=True) if item.is_file()), None)

    @property
    def adb(self) -> Path | None:
        if not self.sdk_root:
            return None
        name = "adb.exe" if os.name == "nt" else "adb"
        return self.sdk_root / "platform-tools" / name

    def health(self) -> VirtualProviderHealth:
        missing = []
        for label, path in (("emulator", self.emulator), ("avdmanager", self.avdmanager), ("adb", self.adb)):
            if path is None or not path.is_file():
                missing.append(label)
        if missing:
            return VirtualProviderHealth(self.provider_id, False, "UNAVAILABLE", f"Android SDK is missing: {', '.join(missing)}")
        return VirtualProviderHealth(
            self.provider_id, True, "READY", None,
            ("images.list", "instance.create", "instance.start", "instance.stop", "instance.reset", "instance.delete", "instance.configure", "instance.endpoint"),
        )

    def list_images(self) -> list[dict[str, Any]]:
        if not self.sdk_root:
            return []
        root = self.sdk_root / "system-images"
        if not root.is_dir():
            return []
        images: list[dict[str, Any]] = []
        for package_file in sorted(root.glob("*/*/*/package.xml")):
            relative = package_file.parent.relative_to(root)
            parts = relative.parts
            if len(parts) != 3:
                continue
            image_id = "system-images;" + ";".join(parts)
            images.append({"id": image_id, "api": parts[0], "variant": parts[1], "abi": parts[2], "installed": True})
        return images

    def create(self, instance: VirtualInstance) -> VirtualInstance:
        self._require_ready()
        instance.config.validate()
        installed = {item["id"] for item in self.list_images()}
        if instance.config.image not in installed:
            raise RuntimeError("Requested Android system image is not installed")
        self.avd_home.mkdir(parents=True, exist_ok=True)
        result = self.runner.run(
            self.avdmanager, ["create", "avd", "--name", instance.name, "--package", instance.config.image, "--force"],
            timeout=90, input_text="no\n", env=self._env(),
        )
        if result.returncode:
            raise RuntimeError("Android Emulator instance creation failed")
        instance.data_path = str((self.avd_home / f"{instance.name}.avd").resolve())
        self._write_display_config(instance)
        instance.state = VirtualInstanceState.CREATED
        instance.last_error = None
        return instance

    def start(self, instance: VirtualInstance) -> VirtualInstance:
        return self._launch(instance, wipe=False)

    def stop(self, instance: VirtualInstance) -> VirtualInstance:
        self._require_ready()
        if instance.adb_endpoint:
            self.runner.run(self.adb, ["-s", instance.adb_endpoint, "emu", "kill"], timeout=15, env=self._env())
        instance.state = VirtualInstanceState.STOPPED
        instance.pid = None
        return instance

    def reset(self, instance: VirtualInstance) -> VirtualInstance:
        if instance.state in {VirtualInstanceState.RUNNING, VirtualInstanceState.STARTING}:
            self.stop(instance)
        return self._launch(instance, wipe=True)

    def delete(self, instance: VirtualInstance) -> None:
        self._require_ready()
        if instance.state in {VirtualInstanceState.RUNNING, VirtualInstanceState.STARTING}:
            self.stop(instance)
        result = self.runner.run(self.avdmanager, ["delete", "avd", "--name", instance.name], timeout=45, env=self._env())
        if result.returncode:
            raise RuntimeError("Android Emulator instance deletion failed")

    def configure(self, instance: VirtualInstance, config: VirtualDeviceConfig) -> VirtualInstance:
        config.validate()
        if instance.state in {VirtualInstanceState.RUNNING, VirtualInstanceState.STARTING}:
            raise RuntimeError("Stop the virtual phone before changing display configuration")
        instance.config = config
        self._write_display_config(instance)
        return instance

    def _launch(self, instance: VirtualInstance, *, wipe: bool) -> VirtualInstance:
        self._require_ready()
        if instance.console_port is None:
            raise RuntimeError("No loopback emulator port was reserved")
        instance.state = VirtualInstanceState.STARTING
        args = [
            "-avd", instance.name, "-port", str(instance.console_port), "-no-window", "-no-audio",
            "-no-boot-anim", "-no-snapshot-save", "-gpu", "swiftshader_indirect", "-netdelay", "none", "-netspeed", "full",
        ]
        if wipe:
            args.append("-wipe-data")
        process = self.runner.start(self.emulator, args, env=self._env())
        instance.pid = int(getattr(process, "pid", 0) or 0) or None
        instance.adb_endpoint = f"emulator-{instance.console_port}"
        deadline = time.monotonic() + self.boot_timeout
        while time.monotonic() < deadline:
            state = self.runner.run(self.adb, ["-s", instance.adb_endpoint, "get-state"], timeout=5, env=self._env())
            if state.returncode == 0 and state.stdout.strip() == "device":
                boot = self.runner.run(self.adb, ["-s", instance.adb_endpoint, "shell", "getprop", "sys.boot_completed"], timeout=5, env=self._env())
                if boot.returncode == 0 and boot.stdout.strip() == "1":
                    instance.state = VirtualInstanceState.RUNNING
                    instance.last_started_at_ms = int(time.time() * 1000)
                    instance.last_error = None
                    return instance
            time.sleep(0.5)
        instance.state = VirtualInstanceState.ERROR
        instance.last_error = "BOOT_TIMEOUT"
        raise RuntimeError("Android Emulator did not boot before the bounded timeout")

    def _write_display_config(self, instance: VirtualInstance) -> None:
        if not instance.data_path:
            return
        config_path = Path(instance.data_path) / "config.ini"
        existing: dict[str, str] = {}
        if config_path.is_file():
            for line in config_path.read_text(encoding="utf-8", errors="replace").splitlines():
                if "=" in line:
                    key, value = line.split("=", 1)
                    existing[key] = value
        existing.update({
            "hw.lcd.width": str(instance.config.width), "hw.lcd.height": str(instance.config.height),
            "hw.lcd.density": str(instance.config.dpi), "disk.dataPartition.size": f"{instance.config.storage_mb}M",
        })
        config_path.parent.mkdir(parents=True, exist_ok=True)
        config_path.write_text("\n".join(f"{key}={existing[key]}" for key in sorted(existing)) + "\n", encoding="utf-8")

    def _env(self) -> dict[str, str]:
        env = dict(os.environ)
        env["ANDROID_AVD_HOME"] = str(self.avd_home)
        if self.sdk_root:
            env["ANDROID_SDK_ROOT"] = str(self.sdk_root)
        return env

    def _require_ready(self) -> None:
        health = self.health()
        if not health.available:
            raise RuntimeError(health.reason or "Android Emulator provider is unavailable")
