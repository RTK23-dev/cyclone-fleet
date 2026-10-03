# Handoff: Cyclone Ports run 4 (phone side): DONE, released in alpha.99

**Status:** run 4 is built and released in `5.0.0-alpha.99.dev1` (version code 244) from
`claude/cyclone-v5-handoff-review-9qrs40`. TODO steps 1–7 below are done; the detail is in plan 48 §7. Physical phone
acceptance remains UNVERIFIED. Next: run 5 (sources, key rotation, rate limits, drift diff, Account Setup verification
points bound to `code.in`) and run 6 (polish, ship).

---

_The original handoff follows, kept for reference._

# Handoff: Cyclone Ports run 4 (phone side), in progress

Branch: `claude/cyclone-ui-updates-emnerc` (feature). Released so far: alpha.96/97/98 (runs 1–3, plan 48). Run 4 is
**not released**. This commit is WIP: everything below marked DONE is written; the Kotlin pure parts passed locally;
nothing Android-wide has been through Mobile CI; the gateway changes were import-checked only.

## Design (decided)
- The phone never calls the PC. The PC's Port Hub **polls** each ready phone (~1.5 s) over the bridge.
- Phone ops: `ports.poll {ack?, max?, drop?}` → `{items}`; `ports.blob {id, offset}` → `{data, bytes, done}` (screenshot
  chunks, 384 KB); `ports.answer {id, state, reason?, plugin?, value?, url?, file?, sealed?}` → `{handled}`;
  `ports.file` = `cc.media` chunks with `taskId: "pt_…"` (image/video/audio to the gallery).
- Items: `{kind: emit|await|cancel, id "pt_…", runId (= mission id), at, app?, routine?, taskId?}` plus
  emit `{port, data, blob?{bytes,mime}}`, await `{port, timeoutS, match{ask}, place?}` (code.in requires place),
  cancel `{item, reason}`. Items stay until acked; an item handed out once is "sent" (a stopped wait then queues a
  cancel instead of silently disappearing). `drop: true` drops the first item if the PC's secret check refuses it.
- "Connected" = PC polled within 20 s; without it the run gets `no_pc` at once.
- code.in: the hub holds the code in memory (`traffic.take_code`), seals it with `ports/seal.py code_envelope(pub_b64,
  fingerprint, run_id, place, code)` to the phone key from `cc.key`; aad = sorted compact JSON
  `{deviceKey, expiresAt(+300 s), leaseId, place, runId, slot:"code"}`, INFO `cyclone-port-code/v1`. The phone opens
  it (`SealedDelivery.openCode`), holds it as slot `code`, and `vault_fill one_time_code` (slot otp) takes `code` first.
  The run/model only learns the length.
- value.in → quoted data to the model (PC `reject_secret_payload` fails closed → answer `failed`); link.in → phone opens
  it, model gets only the host; file.in → hub saved it to `runtime/ports/runs/<run>/files/<name>`, bridge pushes via
  `ports_file` chunks, answers `{file:{name, folder, mime, bytes}}`.

## DONE (uncommitted before this commit, now committed as WIP)
- `apps/device-gateway/cyclone_device_gateway/ports/seal.py`: HPKE base seal (P-256/HKDF-SHA256/AES-256-GCM),
  `fingerprint` (matches `DeviceKey.fingerprint`), `code_envelope`. Cross-checked: Python-sealed fixture opens in Kotlin.
- `apps/mobile/.../ports/PortOutbox.kt` (+ `PortScrub`): pure; `PortOutbox.shared`. Tests:
  `src/test/.../ports/PortOutboxTest.kt` (10, pass locally).
- `secrets/SealedDelivery.kt`: `CODE_INFO`, `CODE_SLOT`, `openCode(missionId, runId, place, json)`. Tests appended to
  `SealedDeliveryTest.kt` (2 new) with fixture `src/test/resources/cyclone-port-code-fixture.json` (pass locally).
- `mind/mission/AndroidMindPorts.fillSecret`: otp slot takes the delivered `code` first.
- `gateway/GatewayV5PortsAdapter.kt` (new), registered in `GatewayProtocol.operations` and `GatewayRuntime` dispatch;
  `CommandMedia` TASK_ID accepts `pt_`.
- Gateway `desktop_runtime/v5_contract.py`: `PORTS_*` constants, `_validate_ports_blob` (before the generic secret
  check, base64 padding could look like `otp=`), `_validate_ports_item/_response`, methods `ports_poll`, `ports_blob`,
  `ports_answer` (sealed → checked=True after shape checks), `ports_file`; ops in `V5_OPS` and
  `cyclone_bridge/protocol.py ALLOWED_OPS`; `import json` added.

## TODO (in order)
1. `ports/phone.py` `PhoneBridge(hub, contract, devices)`: thread, every ~1.5 s for ready devices
   (`paired and state == "ready"`, same as `command/center._ready_devices`; the runtime passes `self.fleet.list_public`
   and `share_contract` — see `desktop_runtime/api.py` ~L208–222): poll with acks of handled ids (dedupe processed ids
   per device); emit → `hub.traffic.emit(runId, port, data, meta{app,routine,taskId}, file={base64,mime} via ports_blob
   for screen.shot)`; await → `hub.traffic.wait(...)`; non-waiting state → answer at once; else a worker does
   `traffic.result(awaitId, 25)` until done, then answer (code: `take_code` + `cc_key` + `seal.code_envelope`; file:
   chunks then answer; value/link: pass on; DesktopRuntimeError on value → answer `failed`). cancel →
   `traffic.cancel(awaitId)`. On a secret refusal of a poll: retry `max=1`, then `drop=True`. Wire start/stop in
   `desktop_runtime/api.py` next to `self.ports`.
2. Mind tools in `mind/PhoneMindToolbox.kt`: constructor param `ports: MindPortsLink?` (pure interface in `mind/`:
   `send(port, data, image)`, `wait(port, ask, timeoutS, place, cancelled)`), specs only when non-null; add
   `port_send`/`port_wait` to `PHONE_TOOLS`, `dispatchTool`, `SPECS`. screen.shot uses `observeAndRender(image=true)`
   `imageDataUrl`; place via `PlaceResolver.resolveCurrent(screen)` supplied by the Android side. Construct in
   `mind/mission/MindMissions.kt:678` only when `PortOutbox.shared.connected()`, runId = `run.id`.
3. Gateway tests: validators, `ports_answer` sealed shape, PhoneBridge end to end with a fake contract and the kit's
   example plugins (pattern: `tests/test_ports_traffic.py`), seal round trip with a python HPKE open.
4. Optional: Glass Activity already shows runs by runId; mention phone runs in the lane copy.
5. Validate (AGENTS.md): gateway pytest, kit tests, `release_versions.py --check`, `mobile_product_guard.py`;
   Mobile CI does the Android compile (no SDK locally; local pure-Kotlin harness was
   `scratchpad/kt/kt.sh` with android stubs).
6. Release alpha.99: mobile code 244, `docs/RELEASE_5.0.0-alpha.99.dev1.md`, Glass unchanged unless touched; lane per
   AGENTS.md "Fast release lane" (push to `claude/cyclone-v5-handoff-review-9qrs40`, wait Mobile CI + publish, verify
   manifest SHA and rotated key, merge back to the feature branch). Physical phone check: state UNVERIFIED.
7. Update plan 48 status line (run 4 built/released) and §7 run 4 detail. Then run 5 (sources, key rotation, rate
   limits, drift diff) and run 6 (polish, ship).

## Invariants to keep
No code/OTP/password ever in logs, DB, model context or Glass; codes only sealed; approval boundaries unchanged;
Task Kit for task buttons; no model ids in commits.
