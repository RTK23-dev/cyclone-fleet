from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field


class ObserveRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    include_screenshot: bool = False
    screenshot: bool | None = None
    uiautomator: bool = True
    mode: Literal["compact", "full"] = "compact"
    goal: str | None = None

    @property
    def wants_screenshot(self) -> bool:
        return self.include_screenshot if self.screenshot is None else self.screenshot


class ActionRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    tool: Literal[
        "phone.observe",
        "phone.find",
        "phone.click",
        "phone.long_press",
        "phone.swipe",
        "phone.scroll",
        "phone.type",
        "phone.back",
        "phone.home",
        "phone.open_app",
        "phone.wait_for",
    ]
    params: dict[str, Any] = Field(default_factory=dict)
    goal: str = ""
    source: Literal["PC_CODEX"] = "PC_CODEX"
    request_id: str | None = None


class DebugBundleRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    expected: str = ""
    goal: str = ""


class TeachStartRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    app_package: str | None = None
    goal: str = ""
    source: Literal["PC_CODEX"] = "PC_CODEX"


class TeachStopRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    compileForReview: bool = True
    source: Literal["PC_CODEX"] = "PC_CODEX"


class SkillSaveRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    goal: str = ""
    app: str = ""
    pageKey: str = ""
    status: str = "draft"
    enabled: bool = False
    storeClass: str = "AutomationStore"
    compiler: str = "SkillCompiler.compile"
    steps: list[dict[str, Any]] = Field(default_factory=list)
    params: dict[str, Any] = Field(default_factory=dict)
    source: Literal["PC_CODEX"] = "PC_CODEX"


class SkillRunRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    dryRun: bool = False
    params: dict[str, Any] = Field(default_factory=dict)
    source: Literal["PC_CODEX"] = "PC_CODEX"
