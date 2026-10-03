# Cyclone Ports SDK

Build plugins that plug into a Cyclone run. A run can send a plugin events, screenshots, the account's fields and
files, and wait on a plugin for a file, a value, a confirmation link or an SMS code. This kit has everything needed to
build and test a plugin **today**, before the gateway's Port Hub ships:

| Piece | What it is |
|---|---|
| `SPEC.md` | The contract `cyclone.ports/1`: ports, manifest, signed requests, deliveries, status codes, security rules |
| `cyclone_ports/` | A small SDK with no dependencies: `PluginServer`, `deliver`, `sign`/`verify`, validators |
| `cyclone_ports/devhub.py` | **Dev Hub**: plays the phone run and the hub, signs requests, serves one-time artifacts, takes deliveries |
| `cyclone_ports/conformance.py` | **Checker**: runs the hub's checks against your running plugin |
| `examples/` | Three working plugins: `logger` (out ports), `pc_images` (`file.in`), `sms_plugin` (`code.in`) |
| `scenarios/` | Runs to play: `signup.json` (fields, screenshot, SMS code), `image.json` (an image from the PC) |
| `schemas/` | JSON Schemas for the manifest, envelope, await request and delivery |
| `tests/` | End-to-end tests, including "the code never lands in any log or file" |

Python 3.10+, standard library only. Plugins in other languages only need HTTP, JSON and HMAC-SHA256: follow
`SPEC.md` and use the checker.

## Quick start (3 terminals)

```bash
cd tools/cyclone-ports-sdk
export CYCLONE_PLUGIN_SECRET=dev-secret SMS_FORWARDER_TOKEN=dev-forwarder

# 1. start the example plugins
python examples/logger/plugin.py --out ./logger-out &                 # :8771
python examples/pc_images/plugin.py --folder ~/Pictures/cyclone &     # :8772
python examples/sms_plugin/plugin.py --source my-second-phone &       # :8773

# 2. check them
python -m cyclone_ports.conformance http://127.0.0.1:8773

# 3. play a sign-up run; it waits for an SMS code
python -m cyclone_ports.devhub scenarios/signup.json \
  --plugin http://127.0.0.1:8771 --plugin http://127.0.0.1:8773 --source my-second-phone

# 4. while it waits, "receive" a text (your SMS forwarder app does this for real)
curl -X POST http://127.0.0.1:8773/sms -H "X-Forwarder-Token: dev-forwarder" \
  -H "Content-Type: application/json" -d '{"from":"Example","text":"Your Example code is 482913"}'
```

The run prints `"state": "delivered", "filled": true, "codeLength": 6`. The Dev Hub never shows, keeps or logs the
code; it would seal it to the phone. `logger-out/runs.jsonl` holds the events and fields, and `logger-out/artifacts/`
holds the screenshot.

The image run works the same way:
`python -m cyclone_ports.devhub scenarios/image.json --plugin http://127.0.0.1:8771 --plugin http://127.0.0.1:8772`.
The newest image in the folder lands in `devhub-runs/run_dev_image/files/`.

Hub logs (metadata only) go to stderr and the step results go to stdout. `--log audit.jsonl` keeps the audit log.

## Write your own plugin (Python)

```python
import os

from cyclone_ports import PluginServer, deliver

manifest = {
    "contract": "cyclone.ports/1", "name": "my-plugin", "version": "0.1.0",
    "endpoint": "http://127.0.0.1:8780",
    "serves": [{"port": "run.event", "way": "out"}, {"port": "value.in", "way": "in"}],
    "needs": {"personal": True},
}
server = PluginServer(manifest, secret=os.environ["CYCLONE_PLUGIN_SECRET"], port=8780)

server.on_out = lambda port, env: print(env["runId"], env["data"].get("stage"))       # out ports
server.on_await = lambda port, req: deliver(req["deliverUrl"], req["token"],           # in ports
                                            {"v": 1, "value": "a caption for the post"})
server.serve_forever()
```

Answer fast: `on_out` and `on_await` run on the request thread, so start a thread for slow work. See
`examples/pc_images`.

Test it: `python -m cyclone_ports.conformance http://127.0.0.1:8780`, then write a scenario like the ones in
`scenarios/` and play it with the Dev Hub.

## Built to keep working as Ports grows

- **Secrets and rotation:** the hub shows the plugin secret as `k<N>.<secret>`. Put it in `CYCLONE_PLUGIN_SECRET`.
  During a rotation, put the new key in `CYCLONE_PLUGIN_SECRET_NEXT`; `PluginServer` accepts both.
- **Tolerant reading:** `PluginServer` checks structure only, so new stages, fields and match keys never break a
  plugin. De-duplicate on the envelope `id`, and treat a repeated `awaitId` as the same wait.
- **Safe retries:** `deliver()` adds a `deliveryId` and retries 429, 5xx and network errors. The hub answers a repeat
  with `200 duplicate`.
- **Your own data points:** extension ports `x.<plugin>.<name>` (SPEC Â§2.1). Optional capabilities go in `features`.
- **Other languages:**
  - `js/verify.mjs` checks signatures in Node;
  - `schemas/signature-vectors.json` lets any language prove its signature check;
  - `schemas/*.schema.json` describe every message.
- Design review and confidence: `Cyclone V5 plan/47-run-ports-review.md`. To hand the work to an agent, send it
  `Cyclone V5 plan/HANDOFF-build-a-connector.md`.

## Scenario format

```json
{
  "run": {"runId": "run_x", "taskId": "task_x", "rowId": "row_x", "app": "com.example.app"},
  "steps": [
    {"emit": "run.event", "data": {"stage": "started"}},
    {"emit": "screen.shot", "pageKey": "signup:birthday", "file": "optional.png"},
    {"await": "code.in", "match": {"from": "Example"}, "timeoutS": 120, "required": true}
  ]
}
```

A required await that times out or fails emits `run.event {stage: needs_you}` and stops the run, like a real run
handing over to the owner.

## Tests

```bash
python -m pytest tools/cyclone-ports-sdk/tests -q
```

CI (`pc-companion-ci.yml`) runs these tests on every pull request that touches the gateway or this kit.

## Status

The contract is a frozen draft. The real Port Hub lands in the gateway in plan 45 runs P1â€“P5. Until then:
- `secret.out` and `secret.in` are hub/vault-only and can't be served by a plugin;
- MCP plugins (`cyclone_port_wait`) and recipes (YAML) come in P5.

Plugins built against this kit keep working once the real hub ships. Builders start at
`Cyclone V5 plan/HANDOFF-run-ports-plugins.md`.

The native **ID Generator** starter ships with Alpha 101 / Glass Alpha 56. Its developer/agent workflow lives in `starters/id-generator/SKILL.md`; Studio 7.2.1+ owns the rendering engine and Glass owns policy/consent. The real gateway Ports hub is now available, including targeted private traffic and correlated waits.
