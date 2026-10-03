from __future__ import annotations

import hashlib
import re
import secrets
import time
from typing import Any

from .models import VirtualDeviceConfig, VirtualInstance
from .ports import LoopbackPortAllocator
from .provider import VirtualDeviceProvider
from .registry import VirtualDeviceRegistry


_INSTANCE_ID = re.compile(r"^vdev_[a-f0-9]{16}$")


class VirtualDeviceService:
    def __init__(self, registry: VirtualDeviceRegistry, providers: list[VirtualDeviceProvider], *, ports: LoopbackPortAllocator | None = None):
        self.registry = registry
        self.providers = {item.provider_id: item for item in providers}
        self.ports = ports or LoopbackPortAllocator()

    def health(self) -> list[dict[str, Any]]:
        return [self.providers[key].health().public() for key in sorted(self.providers)]

    def list_images(self, provider_id: str) -> list[dict[str, Any]]:
        return self._provider(provider_id).list_images()

    def list_instances(self) -> list[dict[str, Any]]:
        return [item.public() for item in self.registry.list()]

    def get(self, instance_id: str) -> dict[str, Any]:
        return self.registry.get(instance_id).public()

    def endpoint(self, instance_id: str) -> dict[str, Any]:
        item = self.registry.get(instance_id)
        return {
            "instanceId": item.instance_id,
            "cycloneDeviceId": item.cyclone_device_id,
            "backend": "ANDROID_ADB",
            "ready": item.state.value == "RUNNING" and bool(item.adb_endpoint),
            "adbSerial": item.adb_endpoint,
            "bindAddress": "127.0.0.1",
            "networkMode": item.config.network_mode,
        }

    def configure(self, instance_id: str, config: VirtualDeviceConfig) -> dict[str, Any]:
        item = self.registry.get(instance_id)
        provider = self._available_provider(item.provider)
        provider.configure(item, config)
        self.registry.save(item)
        return item.public()

    def create(self, provider_id: str, config: VirtualDeviceConfig) -> dict[str, Any]:
        config.validate()
        provider = self._available_provider(provider_id)
        instance_id = f"vdev_{secrets.token_hex(8)}"
        digest = hashlib.sha256(f"cyclone-virtual-v1\0{provider_id}\0{instance_id}".encode()).hexdigest()
        console, _ = self.ports.allocate_emulator_pair(self.registry.allocated_console_ports())
        item = VirtualInstance(
            instance_id=instance_id, provider=provider_id, cyclone_device_id=f"dev_{digest[:20]}",
            name=f"cyclone_{instance_id[5:]}", config=config, console_port=console,
            created_at_ms=int(time.time() * 1000),
        )
        try:
            provider.create(item)
        except Exception:
            self.ports.release_emulator_pair(console)
            raise
        self.registry.save(item)
        return item.public()

    def lifecycle(self, instance_id: str, operation: str) -> dict[str, Any]:
        if not _INSTANCE_ID.fullmatch(instance_id):
            raise KeyError(instance_id)
        item = self.registry.get(instance_id)
        provider = self._available_provider(item.provider)
        if operation == "start":
            provider.start(item)
        elif operation == "stop":
            provider.stop(item)
        elif operation == "reset":
            provider.reset(item)
        elif operation == "delete":
            provider.delete(item)
            if item.console_port is not None:
                self.ports.release_emulator_pair(item.console_port)
            self.registry.remove(instance_id)
            return {"instanceId": instance_id, "deleted": True}
        else:
            raise ValueError("Unsupported virtual lifecycle operation")
        self.registry.save(item)
        return item.public()

    def _provider(self, provider_id: str) -> VirtualDeviceProvider:
        provider = self.providers.get(provider_id)
        if provider is None:
            raise KeyError(provider_id)
        return provider

    def _available_provider(self, provider_id: str) -> VirtualDeviceProvider:
        provider = self._provider(provider_id)
        health = provider.health()
        if not health.available:
            raise RuntimeError(health.reason or "Virtual provider is unavailable")
        return provider
