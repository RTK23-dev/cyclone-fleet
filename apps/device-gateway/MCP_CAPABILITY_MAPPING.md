# Device Gateway capability mapping — Cyclone V3.5

This is the V3.5 contract between `tools/codex-phone-mcp` and the PC Device Gateway. Capability discovery is metadata only; neither MCP nor the PC gateway becomes Android action authority.

## Protocol

```text
cyclone.gateway.capability.v1
```

| Gateway endpoint | MCP responsibility |
|---|---|
| `GET /v1/device/status` | status/readiness |
| `GET /v1/devices`, `GET /v1/fleet`, `POST /v1/fleet/scan` | auto-detect connected phones (fleet surface) |
| `/v1/devices/{device_id}/agent/*` | per-device routing when multiple phones are connected |
| `GET /v1/devices/{device_id}/workspaces` | Layer 2 list (`workspace.list`); display 0 time-sliced lock, not fleet groups |
| `POST /v1/devices/{device_id}/workspaces` | Layer 2 `register/switch/pause/release/arm/next` via Android `action.execute` |
| `GET /v1/fleet/workspace` | Fleet device groups / wall selection — **not** Layer 2 |
| `GET /v1/capabilities` | typed capability discovery/health |
| `POST /v1/observe` | **Legacy** compact observe. Empty or missing JSON body is allowed and uses defaults (`include_screenshot=false`, `mode=compact`). MCP uses `POST /v1/capabilities/observe`. |
| `POST /v1/capabilities/observe` | compact/full structured observation with correlation + witness |
| `GET /v1/ui/search` | deterministic semantic/raw/UiAutomator search |
| `GET /v1/ui/element/{id}` | inspect one observation-scoped candidate |
| `POST /v1/capabilities/action` | typed action proposal; Android authority remains decisive |
| `POST /v1/debug/bundle` | diagnostic evidence |
| `/v1/teach/*` | canonical Follow Me / teaching adapter |

## MCP surface

| MCP tool | V3 concept | Notes |
|---|---|---|
| `phone_devices` | auto-detect | Fleet discovery; degrades to the legacy single-device status row when the gateway only exposes the legacy surface. `scan=true` forces a fresh ADB scan. |
| `phone_status` | status | Device/Gateway/Accessibility readiness. |
| `phone_capabilities` | status/discovery | Cached `GET /v1/capabilities`; `refresh=true` forces rediscovery. |
| `phone_observe` | observe | Returns a bounded Page Card by default: location, text/summary, counts, current candidates, verified route hints, and an optional local screenshot artifact. |
| `phone_locate` | locate | Default PC-agent entry point: readiness + Page Card + semantic search ranked for one stated goal. |
| `phone_ui_search` | search | Use before screenshots when target missing from compact context. |
| `phone_inspect_element` | inspect | Element IDs remain observation-scoped. |
| `phone_act` | act | Typed allowlist only. Requires a current observation-scoped element ID and returns before/after Page Cards, delta, action status, and verification result. After a Layer 2 switch, `params` must include matching `workspaceId` + `workspaceGeneration`. |
| `phone_workspace` | Layer 2 workspace | `operation=list/register/switch/pause/release/arm/next` maps to `workspace.*` (switch also aliases `phone.workspace_switch`). Always `session_id=default-foreground` / `display_id=0`. Switch/next returns `workspaceId` + `workspaceGeneration`. Pause/release are operator glass and do not take AI ownership. GATE is never synthesized. |
| `phone_teach_start/status/stop` | teach | Existing canonical teaching store only. |
| `phone_debug_bundle` | debug | Transport/execution/verification disagreements. |
| `phone_screenshot` | vision fallback | Use only when structured evidence is insufficient/conflicting. |

No MCP tool exposes shell, root, PowerShell, arbitrary ADB or a generic command primitive.

## Per-device routing

Every operation accepts an optional `device_id` returned by `phone_devices`. In multi-phone work,
agents must provide it on every call; it selects the Desktop fleet surface
(`/v1/devices/{device_id}/agent/*`). Omitting it only selects the configured legacy single-device
surface and must never be used as a screenshot or targeting workaround.

The Desktop agent returns its own envelope for actions. The MCP client normalizes it to the
canonical `cyclone.gateway.capability.v1` shape (transport -> execution -> verification) so the
shared fail-closed classifier and fresh-observation rule apply unchanged. The normalization is
client-side only; the gateway and Android authority are untouched.

The Desktop agent never returns image bytes in its semantic response. When requested,
`phone_screenshot` and `phone_observe(include_screenshot=true)` return a bounded per-device local
artifact reference (`LOCAL_FILE`) or an explicit unavailable reason. The PC Companion remains the
live-video surface; debug bundles remain the diagnostic fallback. No call silently targets another
phone to obtain an image.

## Action authority

MCP validates that the requested capability is advertised and forwards a typed V3 request. `user_authorized=true` for `phone.type` is only an MCP intent acknowledgement; it is **not** policy authority.

Android action authority is:

```text
GatewayActionAuthority
  -> final V3.5 Policy Governor/action composition
  -> AUTHORIZED_HANDOFF only
  -> PhoneToolExecutor
```

Observation is the PC-agent authority boundary: element IDs are valid only for the current
observation and are invalidated after every mutation. App Graph/Brain hints are advisory and may
only be learned from Android-verified semantic transitions.

## Correlation and witnesses

Every typed request carries a bounded correlation ID. For actions, the same correlation ID is inherited by the Android NDJSON request envelope.

Mutating action responses preserve a single atomic envelope:

1. before Page Card/witness;
2. transport outcome;
3. Android execution outcome (`android_execution`, bounded/safe fields only);
4. after Page Card/witness plus page delta;
5. verification outcome.

After any mutation, MCP clears its cached observation ID and requires a new observation before another mutation. This prevents accidental reuse of observation-scoped element IDs.

## Error model

Canonical public error codes:

| Code | Meaning | Typical layer |
|---|---|---|
| `CAPABILITY_UNAVAILABLE` | typed capability/Android service unavailable | capability |
| `STALE_OBSERVATION` | expected observation is no longer current | protocol |
| `POLICY_DENIED` | Android V3 policy rejected action | policy |
| `EXECUTION_FAILED` | PhoneToolExecutor failed | execution |
| `VERIFICATION_FAILED` | authoritative after-state did not verify | verification |
| `DEVICE_DISCONNECTED` | ADB/socket/USB transport unavailable | transport |
| `PROTOCOL_MISMATCH` | incompatible/malformed V3 contract | protocol |
| `AUTH_REJECTED` | PC or Android session credential rejected | protocol/auth boundary |
| `GATE` | Android human-review overlay blocks switch/queue/mutate; never auto-approved | Layer 2 |
| `MUTATE_LOCK` | Layer 2 lease holds display 0; missing or wrong `workspaceId`/`workspaceGeneration` | Layer 2 |
| `TARGET_MISMATCH` | Switch/mutate verified the wrong package, user, or display | Layer 2 |
| `STALE_WORKSPACE` | `workspaceGeneration` does not match the current lease | Layer 2 |
| `QUEUE_EMPTY` | `workspace.next` with no armed job | Layer 2 |
| `USER_UNVERIFIED` | Cross-profile identity could not be proven | Layer 2 |

HTTP 200 is never sufficient evidence of phone success. MCP's success flag is computed from the typed transport/execution/verification body.

## USB recovery

The PC Android bridge client automatically checks/recreates:

```text
tcp:8766 -> localabstract:cyclone_gateway
```

before Android bridge requests. Multiple authorized devices require an explicit serial. Unauthorized devices, missing APK, Android Gateway off, Accessibility off and token mismatch remain distinct doctor/readiness states.

## Privacy

- Android session token and PC token are never returned by discovery, doctor or MCP.
- `phone.type` plaintext is not written to reports/audits.
- password/OTP/API-key/provider-token shaped data stays redacted.
- hidden chain-of-thought is never part of the bridge protocol.
