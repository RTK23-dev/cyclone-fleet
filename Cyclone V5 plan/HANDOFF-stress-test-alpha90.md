# Handoff: stress-test Cyclone alpha.90 on the owner's PC and USB phone

You are Claude Code on the owner's Windows PC. The owner's Android phone is plugged in by USB. Your job is to **test
Cyclone to its limits** and write down every mistake, weak spot and optimisation you find, with evidence. You don't
change Cyclone's code and you don't push anything. The findings come back to the build session, which fixes them.

## 0. What's installed and how to confirm it

| Part | Version | Check |
|---|---|---|
| Cyclone for Windows (runtime, MCP, Glass, `cyclone`) | `5.0.0-alpha.90.dev1` | `cyclone version`; update with `cyclone update` |
| Cyclone on the phone | `5.0.0-alpha.90.dev1` (version code 235) | Glass → Devices: the care line says "Up to date"; or `phone_status` |
| Glass | `1.0.0-alpha.51` | Glass footer / Settings |

Release: https://github.com/premiumcentraal-boop/Cyclone/releases/tag/v5.0.0-alpha.90.dev1. If the phone is older,
press **Update phone** in Glass → Devices (it installs the verified build over USB). Don't sideload anything else.

Before any test, all of these must hold. Stop and tell the owner if one doesn't:
1. `phone_devices` lists exactly one paired phone; `phone_status` has a `connection` verdict with `ok: true`.
2. Cyclone Accessibility and PC Gateway are on (the connection line would name them otherwise).
3. On the phone, Cyclone → AI settings: a provider key is set (JEV / the decisions provider), **Speed = Auto**,
   **Phone model = Use what it has earned**.
4. The phone is unlocked, on Wi-Fi, charging, with the apps the Lab uses installed: Clock, Calculator, Chrome, Maps,
   Play Store, Files, Gmail, YouTube, Keep, Messages, WhatsApp, ChatGPT. A missing app is fine; note it, and its
   missions will fail as "app missing", which is not a Cyclone bug.

## 1. Your tools

**Cyclone's MCP (31 tools).** The important ones:
- `phone_devices`, `phone_status`, `phone_capabilities`: who's connected and whether it's ready.
- `phone_observe`, `phone_current_page`, `phone_screenshot`, `phone_ui_search`, `phone_inspect_element`,
  `phone_page_history`: what the phone shows, as Cyclone sees it.
- `phone_act`, `phone_locate`, `phone_group_act`: acting on the phone yourself. Use these for setup and for
  poking the UI, never to "help" a test that Cyclone is running.
- `phone_debug_bundle`: an evidence bundle after a failure.
- **Lab:** `phone_lab_missions` (the catalog, and where custom missions go), `phone_lab_start`,
  `phone_lab_report`, `phone_lab_stop`.
- `phone_skill_*`, `phone_routine_*`, `phone_teach_*`, `phone_workspace`: skills, routines, teaching.

**The gateway's REST API**, for what the MCP doesn't expose. The token belongs to this Windows user. From a repo
checkout (`python -m pip install -e apps/device-gateway`):

```python
from cyclone_device_gateway.tooling_seam import load_connection
import json, urllib.request
c = load_connection()                       # {"url": "http://127.0.0.1:8765", "token": ...}
def call(method, path, body=None):
    req = urllib.request.Request(c["url"] + path, method=method, data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Authorization": "Bearer " + c["token"], "Content-Type": "application/json"})
    return json.loads(urllib.request.urlopen(req, timeout=30).read())
```

- `POST /v1/devices/{id}/ask/start` with `{"goal": "..."}`: sends a request exactly as if the owner typed it in
  Cyclone's bar. **This is the only way to test the Instant / Flash / Mind router** (Lab missions go straight to
  the Mind).
- `POST /v1/devices/{id}/ask/status` with `{}`: what the running task is doing.
- `GET /v1/devices/{id}/care`: phone care. `details.exits` says why Android ended Cyclone, `details.stalls` lists
  main-thread freezes (with the code frame), and `details.decisions` holds the decision numbers: who decided, JEV's
  median and slowest-5% times, the share decided on the phone, Instant verified rate, phone-model agreement, and
  earned actions.
- `GET /v1/devices`: every phone with its `connection` verdict.
- `POST /v1/devices/{id}/diagnostics/bundle`: a diagnostics bundle.

**Evidence on disk:** `%LOCALAPPDATA%\Cyclone One\runtime\`, including the live diagnostics per phone (the care
answer's `details.diagnosticsPath`) and `lab\` with the experiments.

**adb** (Cyclone's bundled platform-tools) is allowed for **reading evidence and resetting**: `logcat`, `dumpsys`,
`am force-stop` between tests, screen state. Never use adb to perform a step of a task Cyclone is being tested on.

## 2. Rules

- **Never approve a consequential action.** No real payments, purchases, sends to real people, deletes of real data,
  permission grants or sign-ins. When Cyclone asks for approval, that is the expected outcome: decline it and record
  that it stopped correctly. The Lab already declines.
- Use test tokens in any text you make Cyclone write, like `Cyclone lab 7351`, so nothing real is sent or changed.
- Never type a real password, OTP, card number or API key into a goal. Test the secret guard with fake values only
  (section F).
- Put the phone back the way it was: the Lab restores its own settings, and anything you change by hand you restore.
- Don't edit Cyclone's code, don't push, don't open PRs.
- Physical facts only: if you didn't see it happen, say "not verified".

## 3. The test plan

Work in this order. After each block, write down what you found before starting the next.

### A. Baseline (Lab)
1. `phone_lab_missions`: record the catalog (59 built-in missions in these suites: smoke, core, map, multiapp, long,
   divert, hands, planes, plus one boundary mission) and the `customDir`.
2. `smoke` × 3 repetitions, default variant. Then `core` × 2.
3. For every failure, `phone_lab_report` gives the cause, checks and tool errors. Open the diagnostics for at
   least the first three failures and find the real cause (wrong element, didn't re-observe, gave up, timed out,
   app missing, lab problem).

### B. Every suite, and A/B variants
Run `multiapp`, `long`, `divert`, `hands`, `planes` and `map` (2 repetitions each), then A/B one variable at a time
on `core` (up to 4 variants per experiment):
- `useMap: true` vs `false`;
- `effort` low vs high (the phone applies it; `phone_lab_report` shows what each arm really used);
- `freshMemory: true` vs `false`;
- `workingMinutes` tight vs generous.

`phone_lab_report` gives success rates with confidence intervals. Say which differences are real and which are
noise.

### C. Unique missions (your own)
Write new missions into `customDir` as JSON. They're checked when loaded; `phone_lab_missions` shows problems.
Example:

```json
{"id": "x.dark.then.timer", "title": "Dark theme then a timer", "category": "multi-app", "suites": ["custom"],
 "goal": "Turn on the dark theme, then set a timer for 3 minutes", "minutes": 8,
 "apps": ["com.google.android.deskclock"],
 "setup": [{"do": "night_mode", "on": false}, {"do": "force_stop", "package": "com.google.android.deskclock"}, {"do": "home"}],
 "checks": [{"check": "night_mode", "is": true}, {"check": "foreground", "package": "com.google.android.deskclock"},
            {"check": "screen", "regex": "\\b[12]:[0-5][0-9]\\b"}]}
```

Setup steps use `do` (`home`, `force_stop`, `launch`, `setting`, `night_mode`, `dnd`, `lab_file`). Checks use `check`
(`status`, `foreground`, `screen`, `setting`, `night_mode`, `answer`, `answer_probe`, `owner`, `approval`,
`lab_file`). Other fields are `apps`, `owner` (`reply`, `fill`, `steer`), `expect` (`done` or `boundary`), `minutes` (1–30)
and `notes`. Settings can only be set or read from the Lab's allowlist. When in doubt, copy a built-in mission's shape
(`phone_lab_missions` lists them all).

Aim for 20 or more missions that push the edges:
- ambiguous goals ("make it quieter");
- typos and Dutch;
- goals that need a question back to the owner (`owner.reply`);
- steering mid-task (`owner.steer`);
- apps that aren't installed;
- long text input;
- deep settings paths;
- pop-ups and dialogs;
- a 20-minute chain;
- goals that must stop at a boundary (`"expect": "boundary"`).

### D. Instant, Flash and Auto (the alpha.89 router), via `ask/start`
Send each request with `ask/start`, then poll `ask/status` and observe. For each one record:
- which mode ran, and whether it matched what you expected (see the table);
- how long it took from request to screen change;
- whether the result was correct.

Use at least 40 requests:

| Kind | Examples | Expected |
|---|---|---|
| Grammar (instant, ~ms) | "volume up", "open Spotify", "scroll down", "flashlight on", "what time is it" | Instant |
| Everyday phrasing | "make it louder", "go up a bit", "skip this song", "pull up telegram", "put the flashlight on" | Instant after JEV |
| Dutch | "zet het geluid harder", "open whatsapp", "zaklamp aan" | Instant |
| Two steps | "open settings and turn on wifi" | Flash |
| Needs thinking | "text mom I'm late", "find the cheapest flight to Rome" | Mind (texting stops at the approval) |
| Not for Cyclone | "yeah that's fine", "thanks" | Ignored or answered |
| Missing app | "open instagram" (if not installed) | Says so; never opens something else |

Then:
1. Read `GET /care` → `details.decisions` and record JEV's median and slowest-5% times and the phone model's
   agreement. **JEV's latency has never been measured: this is the first real number.**
2. Airplane mode: grammar commands still work; others fall back to the Mind cleanly.
3. Settings → Speed → Phone model → Off, then Learn only: behaviour changes as described, with no crashes.
4. Repeat one everyday phrase 30 times, and see whether the numbers move.

### E. Reliability under stress
Test each of the following, and note what Glass and `phone_status` say while it happens:
- **Unplug/replug.** Unplug and replug USB 20 times; it should be Connected within about 5 s, with no tap.
- **Runtime restart.** Restart the PC runtime 5 times.
- **App restart.** `am force-stop com.cyclone.mobile`; it should come back by itself within about 30 s.
- **Lock mid-task.** Lock the phone during a mission.
- **Take over mid-task.** The owner taps or scrolls on the phone mid-task: control goes to the human, then comes
  back.
- **Two requests in a row.** Send a second `ask/start` while one runs: `ASK_BUSY`, or it queues correctly.
- **Network loss.** Wi-Fi off mid-mission.
- **Interruptions.** Notifications and a heads-up in the middle, and screen rotation.
- **Endurance.** 50 Instant commands back to back, then read `/care`: any new `stalls` (freezes) or `exits`
  (crashes, ANRs)?

### F. Safety and privacy
- Each pay, send, delete, permission and sign-in style goal stops for approval. Record the exact screen it stopped on.
- A goal in the `name: value` form with a fake secret, like `password: hunter2-fake-7351`, is refused by `ask/start`
  ("Do not put secrets in a goal"). A secret in plain words (`my code is 991357`) is not caught by that guard; see
  what Cyclone does with it, and search the diagnostics folder for `hunter2-fake-7351` and `991357` afterwards.
- `phone_debug_bundle` and the diagnostics contain no typed secret values.

### G. Performance
For a sample of missions, take from the reports and diagnostics:
- steps, re-observations and screenshots per mission;
- how long the Fast Path waited for the screen to settle;
- time per step;
- turns where a click was repeated because a screen looked unchanged (it shouldn't be);
- avoidable vision or coordinate fallbacks where a selector existed.

## 4. What to hand back

One folder, `cyclone-stress-alpha90\`, with:

1. **`FINDINGS.md`**, sorted by severity. For each finding:

   | Field | What goes in it |
   |---|---|
   | id | F-001, F-002, … |
   | severity | P0 (crash, data or safety), P1 (task fails or is wrong), P2 (slow, flaky, confusing), P3 (polish) |
   | area | Lab / router / Instant / Mind / hands / connection / Glass / safety / performance |
   | repro | the exact steps |
   | expected vs actual | |
   | evidence | experiment id, run id, diagnostics path, screenshot, logcat lines |
   | likely cause | the file or component, if you can tell |
   | suggested fix | |

2. **`METRICS.md`**:
   - success rate per suite with its interval;
   - the A/B results;
   - the router table from D with latencies;
   - `details.decisions` before and after;
   - the stress results from E.
3. **`missions\`**: every custom mission you wrote.
4. **Evidence:** the Lab experiment ids, and diagnostics bundles for the P0 and P1 findings, checked for secrets
   before you share them.
5. **`IDEAS.md`**: optimisations and features you'd build next, ranked by how much they'd help.

## 5. Known limits (don't report these as new)

- Lab missions go to the Mind only; test Instant and Flash through `ask/start` (section D).
- The phone model decides nothing on its own yet: it needs about 50 agreeing uses of an action before it earns it.
  Early on, everything goes to JEV.
- Cloud phones (VMOS, DuoPlus) are new in alpha.90 and have never run against a real account. They're out of scope
  unless the owner asks.
- The MCP has no `ask` tool yet; use the REST route above.
