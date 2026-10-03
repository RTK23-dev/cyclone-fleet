# 08 — Protocol and gateway 5.0

Phone is source of truth. Glass is a replica + command surface. MCP is a constrained pipe.

Gateway/MCP **must ship 5.0 with mobile 5.0-alpha.1**, not “later.” 4.8 already drifted (mobile 4.8.0, pipe 4.1.0). Do not repeat that.

## New ops (names indicative)

All mutating/observe-of-run ops require `session_id`. Named workspace still requires `display_id > 0`. Companion owning input still returns `HUMAN_HAS_CONTROL`.

```text
atlas.places
atlas.get(placeId, persona)
atlas.diff(placeId, since)

mapping.start | pause | stop | status

ask.start | status                 # goal text only

apps.list                          # installed apps + web places, installed version, mapped versions, needs-remap
atlas.versions(placeId)            # maps per app version + per-version diff
scenarios.list(placeId) | get(id)  # routes to end results + health from runs

runs.list(filter, cursor)          # every run: Ask, Glass Ask, mapping, automations
runs.get(runId)                    # steps, rooms, decision source, cause of death, route
runs.frame(runId, step, which)     # redacted before/after JPEG, only when kept

secrets.slots                      # booleans
secrets.request                    # slot name; phone/Glass shows card

people.search                      # local people memory, not a Facebook scrape
```

**No secret values in args, results, traces, websocket payloads, or access logs.**

Schemas land in:

```text
protocol/cyclone-atlas-v1.schema.json
protocol/cyclone-secrets-v1.schema.json
```

Extend `protocol/cyclone-live-v1.schema.json` only if live JPEG needs a mapping-cursor overlay; prefer a sidecar event `mapping.cursor` on the fleet websocket.

## Glass serving

The local gateway also serves the Glass web bundle (`apps/glass` build) on `127.0.0.1` only, with a per-launch session secret. Glass talks to the ops above over the same origin. Glass has no other backend and no model access.

## MCP

- Cursor / Grok local MCP: add read-only `atlas_query` so agents can *see* the map.
- `mapping.start` is Glass/UI or an explicit operator tool, not a silent side effect of chat.
- Remote MCP (`GATEWAY_MODE`) stays **readonly** by default. Mapping is a local operator act.
- `phone_act` still requires `session_id`. Glass Ask does not bypass GATE.

## Fleet events

Add (names indicative):

```text
atlas.updated
mapping.started | mapping.progress | mapping.stopped
ask.needs_secret
place.stale
```

Glass Maps subscribes to `atlas.updated` / `mapping.progress` to spawn cards without polling the full graph.

## Version matrix

When alpha.1 cuts, `release/version.toml` must agree:

| Key | Value |
|---|---|
| `components.mobile` | `5.0.0-alpha.1` (or the cut’s id) |
| `components.pc_companion` | Cyclone One's own line (1.x). **Not** Glass |
| `components.glass` | Glass web app, starting `1.0.0-alpha.1` |
| `components.device_gateway` | `5.0.0-alpha.1` |
| `components.mcp` | `5.0.0-alpha.1` |

**Numbering (corrected 2026-09-23):** Glass is its own component with its own line. The `pc_companion 1.6.0-alpha.x` builds that carried V5 pages inside Cyclone One were a prototype; they do not count as Glass releases.
