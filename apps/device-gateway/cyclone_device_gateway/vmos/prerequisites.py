from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path, PureWindowsPath
import re
from typing import Final


CURRENT_ONE_VERSION: Final = "1.5.1"
MIN_ONE_VERSION: Final = "1.5.1"
CURRENT_ONE_INSTALLER: Final = "Cyclone-PC-Companion-1.5.1-Setup.exe"
CURRENT_ONE_INSTALL_ROOT: Final = r"%LOCALAPPDATA%\Cyclone One"
BUNDLED_PLATFORM_TOOLS_VERSION: Final = "37.0.1"
BUNDLED_ADB_RELATIVE_PATH: Final = r"android-platform-tools\adb.exe"
CURRENT_MOBILE_BASELINE: Final = "4.3.6"
CURRENT_MOBILE_APK: Final = "Cyclone-4.3.6.apk"
CURRENT_MOBILE_SHA256: Final = "4894dd8c0a69d3445d81b2f33c98ceef86630a0951d273912bd240b87b27dc17"
MIN_WINDOWS_MAJOR: Final = 10
MIN_ANDROID_MAJOR: Final = 13
PREFERRED_ANDROID_MAJOR: Final = 15
SUPPORTED_ANDROID_MAJORS: Final = (13, 14, 15)
VMOS_REMOTE_ADB_DEFAULT_HOURS: Final = 24
VMOS_EDGE_CONTROL_API_MIN_CBS: Final = "1.1.1.10"
VMOS_EDGE_ANDROID15_CONTROL_API_MIN_IMAGE: Final = "vcloud_android15_edge_20260110"

_MOBILE_APK_RE = re.compile(r"^Cyclone-(\d+)\.(\d+)\.(\d+)\.apk$", re.IGNORECASE)
_EDGE_ANDROID15_IMAGE_RE = re.compile(r"^vcloud_android15_edge_(\d{8})(?:\d{6})?$")


def _version_tuple(value: str) -> tuple[int, ...]:
    try:
        return tuple(int(part) for part in value.split("."))
    except ValueError:
        return ()


def _artifact_name(path: str) -> str:
    """Return the leaf name for either Windows or host-native paths.

    VMOS setup is operated from Windows, while contract tests also run on Linux. Using Path.name
    alone on Linux treats backslashes as ordinary characters and can silently bypass Mobile
    filename/version/hash guards. Treat a path containing backslashes as a Windows path.
    """
    return PureWindowsPath(path).name if "\\" in path else Path(path).name


def _mobile_version(path: str) -> tuple[int, ...] | None:
    match = _MOBILE_APK_RE.fullmatch(_artifact_name(path))
    if not match:
        return None
    return tuple(int(part) for part in match.groups())


def _edge_android15_image_supported(image: str) -> bool:
    minimum = _EDGE_ANDROID15_IMAGE_RE.fullmatch(VMOS_EDGE_ANDROID15_CONTROL_API_MIN_IMAGE)
    candidate = _EDGE_ANDROID15_IMAGE_RE.fullmatch(image)
    if minimum is None or candidate is None:
        return False
    return int(candidate.group(1)) >= int(minimum.group(1))


@dataclass(frozen=True)
class InstallStep:
    order: int
    component: str
    action: str
    reason: str

    def public(self) -> dict[str, object]:
        return {
            "order": self.order,
            "component": self.component,
            "action": self.action,
            "reason": self.reason,
        }


INSTALL_ORDER: Final = (
    InstallStep(
        1,
        "Cyclone One",
        f"Install Cyclone One {CURRENT_ONE_VERSION} with {CURRENT_ONE_INSTALLER}; it installs per-user under {CURRENT_ONE_INSTALL_ROOT}.",
        f"One carries CyclonePCRuntime/PC Agent, CycloneAgentMCP, CycloneLivePhone and pinned Android Platform-Tools {BUNDLED_PLATFORM_TOOLS_VERSION}. There is no separate PC Agent or ADB installation.",
    ),
    InstallStep(
        2,
        "VMOS Cloud",
        "Create/select one hosted VMOS Cloud Android 15 phone. Android 13/14 are compatibility targets; Android 10 cannot install current Cyclone Mobile.",
        "Cyclone Mobile minSdk is 33 (Android 13). The standard hosted VMOS path does not require a VMOS Edge image ID.",
    ),
    InstallStep(
        3,
        "VMOS Remote ADB",
        f"Use One's bundled {BUNDLED_ADB_RELATIVE_PATH}. Ensure VMOS has authorized remote ADB for the account, then open the cloud phone → Local Debugging → ADB and complete VMOS's generated SSH/key + adb connection flow until adb reports state=device.",
        f"VMOS documents the manual Local Debugging ADB session as valid for {VMOS_REMOTE_ADB_DEFAULT_HOURS} hours. Pairing/trust has not started yet.",
    ),
    InstallStep(
        4,
        "Cyclone Mobile",
        f"Download the signed published {CURRENT_MOBILE_APK} (Mobile {CURRENT_MOBILE_BASELINE}) and verify SHA-256 {CURRENT_MOBILE_SHA256}.",
        "The VMOS phone must run package com.cyclone.mobile and launcher .MainActivity.",
    ),
    InstallStep(
        5,
        "Install + launch",
        "Run scripts/vmos/check-prerequisites.ps1, then scripts/vmos/install-mobile.ps1 against the connected VMOS serial. Both default to One's bundled adb.exe.",
        "The scripts verify the ADB runtime, Android API level, package installation, launcher start and a live com.cyclone.mobile PID. Pairing/trust is checklist item 3.",
    ),
)


@dataclass(frozen=True)
class PrerequisiteSnapshot:
    windows_major: int
    cyclone_one_version: str | None
    adb_available: bool
    mobile_apk: str | None
    vmos_android_major: int | None
    vmos_adb_account_authorized: bool
    vmos_adb_session_open: bool
    adb_version: str | None = None
    mobile_sha256: str | None = None
    vmos_image: str | None = None
    needs_edge_control_api: bool = False
    vmos_edge_cbs_version: str | None = None


@dataclass(frozen=True)
class PrerequisiteResult:
    ready: bool
    blockers: tuple[str, ...]
    warnings: tuple[str, ...]

    def public(self) -> dict[str, object]:
        return {"ready": self.ready, "blockers": list(self.blockers), "warnings": list(self.warnings)}


def installation_plan() -> list[dict[str, object]]:
    return [step.public() for step in INSTALL_ORDER]


def validate_prerequisites(snapshot: PrerequisiteSnapshot) -> PrerequisiteResult:
    blockers: list[str] = []
    warnings: list[str] = []

    if snapshot.windows_major < MIN_WINDOWS_MAJOR:
        blockers.append("Windows 10 or later is required for the supported Cyclone One + VMOS operator path.")

    if snapshot.cyclone_one_version is None:
        blockers.append(f"Install Cyclone One {CURRENT_ONE_VERSION}; this is the first One build with the pinned bundled ADB runtime.")
    else:
        one_version = _version_tuple(snapshot.cyclone_one_version)
        if not one_version:
            blockers.append("Cyclone One version could not be verified from the installed executable.")
        elif one_version < _version_tuple(MIN_ONE_VERSION):
            blockers.append(f"Cyclone One {MIN_ONE_VERSION} or newer is required; found {snapshot.cyclone_one_version}.")
        elif snapshot.cyclone_one_version != CURRENT_ONE_VERSION:
            warnings.append(f"Validated VMOS baseline is Cyclone One {CURRENT_ONE_VERSION}; found newer {snapshot.cyclone_one_version}.")

    if not snapshot.adb_available:
        blockers.append(
            f"Cyclone One bundled adb.exe is unavailable. Repair/reinstall One {CURRENT_ONE_VERSION}; expected {CURRENT_ONE_INSTALL_ROOT}\\{BUNDLED_ADB_RELATIVE_PATH}."
        )
    elif snapshot.adb_version is None:
        warnings.append(f"Verify the bundled ADB runtime is Platform-Tools {BUNDLED_PLATFORM_TOOLS_VERSION}.")
    else:
        adb_version = _version_tuple(snapshot.adb_version)
        baseline = _version_tuple(BUNDLED_PLATFORM_TOOLS_VERSION)
        if not adb_version:
            blockers.append("ADB Platform-Tools version could not be parsed.")
        elif adb_version < baseline:
            blockers.append(f"ADB Platform-Tools {BUNDLED_PLATFORM_TOOLS_VERSION} or newer is required; found {snapshot.adb_version}.")
        elif snapshot.adb_version != BUNDLED_PLATFORM_TOOLS_VERSION:
            warnings.append(
                f"Validated bundled ADB baseline is Platform-Tools {BUNDLED_PLATFORM_TOOLS_VERSION}; found {snapshot.adb_version}."
            )

    if not snapshot.mobile_apk:
        blockers.append(f"Signed {CURRENT_MOBILE_APK} or a newer Cyclone Mobile APK is required.")
    elif Path(snapshot.mobile_apk).suffix.lower() != ".apk":
        blockers.append("Cyclone Mobile install artifact must be an .apk file.")
    else:
        mobile_version = _mobile_version(snapshot.mobile_apk)
        if mobile_version is not None and mobile_version < _version_tuple(CURRENT_MOBILE_BASELINE):
            blockers.append(f"Cyclone Mobile {CURRENT_MOBILE_BASELINE} or newer is required; found {'.'.join(map(str, mobile_version))}.")
        mobile_name = _artifact_name(snapshot.mobile_apk)
        if mobile_name.lower() == CURRENT_MOBILE_APK.lower():
            if snapshot.mobile_sha256 is None:
                warnings.append(f"Verify {CURRENT_MOBILE_APK} SHA-256 before install.")
            elif snapshot.mobile_sha256.lower() != CURRENT_MOBILE_SHA256:
                blockers.append(f"{CURRENT_MOBILE_APK} SHA-256 does not match the published release asset.")

    if snapshot.vmos_android_major is None:
        blockers.append("VMOS Android version is unknown; select a VMOS instance before continuing.")
    elif snapshot.vmos_android_major not in SUPPORTED_ANDROID_MAJORS:
        if snapshot.vmos_android_major < MIN_ANDROID_MAJOR:
            blockers.append("Cyclone Mobile minSdk 33 requires Android 13 or newer.")
        else:
            warnings.append(f"Android {snapshot.vmos_android_major} is newer than the validated VMOS 13/14/15 target set.")
    elif snapshot.vmos_android_major != PREFERRED_ANDROID_MAJOR:
        warnings.append(f"Android {PREFERRED_ANDROID_MAJOR} is the preferred VMOS target; Android {snapshot.vmos_android_major} is compatibility mode.")

    if not snapshot.vmos_adb_account_authorized:
        blockers.append("VMOS remote ADB permission is not authorized for this account; request/enable ADB access before setup.")
    if not snapshot.vmos_adb_session_open:
        blockers.append("Open VMOS Local Debugging → ADB and establish VMOS's generated SSH/ADB connection before installing Cyclone Mobile.")

    if snapshot.needs_edge_control_api:
        cbs = _version_tuple(snapshot.vmos_edge_cbs_version or "")
        if not cbs or cbs < _version_tuple(VMOS_EDGE_CONTROL_API_MIN_CBS):
            blockers.append(f"VMOS Android Control API requires CBS {VMOS_EDGE_CONTROL_API_MIN_CBS} or newer.")
        if snapshot.vmos_android_major == 15:
            if not snapshot.vmos_image:
                blockers.append(
                    f"VMOS Edge Android 15 Control API requires image {VMOS_EDGE_ANDROID15_CONTROL_API_MIN_IMAGE} or newer."
                )
            elif not _edge_android15_image_supported(snapshot.vmos_image):
                blockers.append(
                    f"VMOS Edge Android 15 Control API requires image {VMOS_EDGE_ANDROID15_CONTROL_API_MIN_IMAGE} or newer."
                )

    return PrerequisiteResult(ready=not blockers, blockers=tuple(blockers), warnings=tuple(warnings))
