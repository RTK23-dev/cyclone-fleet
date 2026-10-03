from __future__ import annotations

from dataclasses import asdict, dataclass
from enum import StrEnum
from typing import Final


CYCLONE_MOBILE_PACKAGE: Final = "com.cyclone.mobile"
CYCLONE_ONE_COMPONENT: Final = "apps/pc-companion"
DEVICE_GATEWAY_COMPONENT: Final = "apps/device-gateway"
PHONE_MUTATION_ENGINE: Final = "PhoneToolExecutor"


class VmosRole(StrEnum):
    """VMOS is infrastructure and transport, never Cyclone's policy or mutation engine."""

    ANDROID_HOST = "ANDROID_HOST"


class LifecycleOwner(StrEnum):
    VMOS_OPENAPI = "VMOS_OPENAPI"


class TransportKind(StrEnum):
    REMOTE_ADB = "REMOTE_ADB"
    VMOS_H5 = "VMOS_H5"


@dataclass(frozen=True)
class VmosArchitecture:
    provider: str = "VMOS"
    role: VmosRole = VmosRole.ANDROID_HOST
    lifecycle_owner: LifecycleOwner = LifecycleOwner.VMOS_OPENAPI
    bootstrap_transport: TransportKind = TransportKind.REMOTE_ADB
    optional_view_transport: TransportKind = TransportKind.VMOS_H5
    mobile_package: str = CYCLONE_MOBILE_PACKAGE
    companion_component: str = CYCLONE_ONE_COMPONENT
    gateway_component: str = DEVICE_GATEWAY_COMPONENT
    mutation_engine: str = PHONE_MUTATION_ENGINE
    provider_native_mutation_allowed: bool = False
    generic_shell_exposed_to_model: bool = False

    @property
    def control_path(self) -> tuple[str, ...]:
        return (
            "Cyclone One",
            "Device Gateway",
            "Cyclone Mobile local gateway",
            self.mutation_engine,
            "Android Accessibility/root helpers",
        )

    @property
    def bootstrap_path(self) -> tuple[str, ...]:
        return (
            "VMOS OpenAPI",
            "VMOS remote ADB",
            self.mobile_package,
            "Cyclone Mobile local gateway",
        )

    def validate(self) -> None:
        if self.mobile_package != CYCLONE_MOBILE_PACKAGE:
            raise ValueError("VMOS targets must run the canonical Cyclone Mobile package")
        if self.mutation_engine != PHONE_MUTATION_ENGINE:
            raise ValueError("VMOS cannot replace PhoneToolExecutor")
        if self.provider_native_mutation_allowed:
            raise ValueError("VMOS native touch/ADB may not become the agent mutation engine")
        if self.generic_shell_exposed_to_model:
            raise ValueError("Generic VMOS/ADB shell control may not be exposed to the model")
        if self.control_path[-2] != PHONE_MUTATION_ENGINE:
            raise ValueError("Canonical phone mutation must terminate at PhoneToolExecutor")

    def public(self) -> dict[str, object]:
        self.validate()
        payload = asdict(self)
        payload["role"] = self.role.value
        payload["lifecycle_owner"] = self.lifecycle_owner.value
        payload["bootstrap_transport"] = self.bootstrap_transport.value
        payload["optional_view_transport"] = self.optional_view_transport.value
        payload["controlPath"] = list(self.control_path)
        payload["bootstrapPath"] = list(self.bootstrap_path)
        payload["architectureVersion"] = 1
        return payload


VMOS_ARCHITECTURE: Final = VmosArchitecture()
VMOS_ARCHITECTURE.validate()
