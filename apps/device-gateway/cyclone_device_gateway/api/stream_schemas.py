from __future__ import annotations

from pydantic import BaseModel, ConfigDict, Field


class StreamSnapshotMetadata(BaseModel):
    """Metadata returned alongside a raw snapshot frame (the frame itself is image bytes)."""

    model_config = ConfigDict(extra="forbid")
    device_id: str = Field(min_length=1, max_length=120)
    profile: str = Field(pattern="^(thumbnail|focus)$")
    codec: str = Field(min_length=1, max_length=40)
    width: int | None = Field(default=None, ge=1)
    height: int | None = Field(default=None, ge=1)
    timestamp_ms: int = Field(ge=0)
    sequence: int = Field(ge=0)


class StreamStatusResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")
    ok: bool = True
    device_id: str = Field(min_length=1, max_length=120)
    protocol: str = Field(min_length=1, max_length=80)
    video: dict
