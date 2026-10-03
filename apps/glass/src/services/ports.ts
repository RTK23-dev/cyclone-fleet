/**
 * Cyclone Ports (plan 48): the gateway's Port Hub, seen from Glass. The owner adds plugins, allows each port with a
 * switch, gives the plugin its key once, and watches it pass its checks and stay healthy.
 *
 * A plugin key appears in exactly two answers (adding a plugin, making a new key), once. Glass never stores it.
 */
import type { GatewayClient } from "./gateway.js";

export type PortWay = "out" | "in";
export type Sensitivity = "public" | "personal" | "secret";
export type PluginStatus =
  | "active" | "waiting_key" | "failing_checks" | "unreachable" | "paused" | "needs_review" | "key_lost";

export interface ServedPort {
  port: string;
  way: PortWay;
  sensitivity: Sensitivity;
  summary: string;
  extension: boolean;
  allowed: boolean;
}

export interface CheckItem {
  name: string;
  ok: boolean;
  required: boolean;
  detail: string;
  hint: string;
  /** "key": it failed only because the plugin doesn't have its key yet. */
  cause: "key" | "";
}

export interface PendingChange {
  version: string;
  added: string[];
  removed: string[];
  needsPersonal: boolean;
  featuresAdded: string[];
  endpointChanged: boolean;
}

export interface Plugin {
  name: string;
  title: string;
  description: string;
  version: string;
  endpoint: string;
  remote: boolean;
  status: PluginStatus;
  paused: boolean;
  serves: ServedPort[];
  features: string[];
  kid: string;
  health: "ok" | "failing" | "unknown";
  healthDetail: string;
  seenAt: number | null;
  checkedAt: number | null;
  checks: { passed: boolean; total: number; failed: number; items: CheckItem[] } | null;
  pending: PendingChange | null;
  createdAt: number;
}

export interface CatalogPort {
  port: string;
  way: PortWay;
  sensitivity: Sensitivity;
  summary: string;
  pluginServed: boolean;
  servedBy: string[];
}

export interface Activity {
  id: number;
  at: number;
  plugin: string;
  kind: string;
  port: string | null;
  runId: string | null;
  status: number | null;
  ok: boolean;
  latencyMs: number | null;
  detail: string;
}

export type BindingState = "ok" | "empty" | "conflict" | "off" | "unavailable" | "vault";

export interface Candidate {
  name: string;
  title: string;
  live: boolean;
  status: PluginStatus;
}

/** One port on the Port map: who could serve it, who does for this scope, and where that came from. */
export interface BindingRow {
  port: string;
  way: PortWay;
  sensitivity: Sensitivity;
  pluginServed: boolean;
  extension: boolean;
  candidates: Candidate[];
  state: BindingState;
  effective: string[];
  /** null: automatic. [] : off. */
  chosen: string[] | null;
  /** "automatic", "default", "routine:<id>", "app:<package>" or "hub". */
  source: string;
  /** This scope itself made a choice for this port. */
  override: boolean;
  inherits: { state: BindingState; effective: string[]; source: string } | null;
}

export interface BindingsView {
  scope: string;
  scopes: Array<{ scope: string; choices: number }>;
  ports: BindingRow[];
  plugins: Array<{ name: string; title: string; status: PluginStatus }>;
}

/** A run that used a port (run 3), from the activity log. */
export interface PortRun {
  runId: string;
  firstAt: number;
  lastAt: number;
  messages: number;
  failures: number;
}

export interface PortWait {
  awaitId: string;
  port: string;
  plugin: string;
  state: "waiting" | "delivered" | "timed_out" | "cancelled" | "failed";
  timeoutAt: number;
  createdAt: number;
  result: Record<string, unknown> | null;
}

export type StepState = "next" | "now" | "ok" | "bad" | "skipped";

/** A test run the owner started from Glass: a fixed scenario played through the real hub. */
export interface TestRun {
  runId: string;
  scenario: string;
  title: string;
  state: "running" | "done" | "needs_you" | "stopped";
  routine: string | null;
  app: string | null;
  startedAt: number;
  finishedAt: number | null;
  steps: Array<{ kind: "emit" | "await"; port: string; state: StepState; detail: string; label: string }>;
}

export const TEST_SCENARIOS: Array<{ id: string; title: string; about: string; ports: string[] }> = [
  { id: "events", title: "Run events", about: "A run starts, writes a note and finishes. Every plugin on those ports gets them.", ports: ["run.event", "log.line"] },
  { id: "signup", title: "Sign-up with a code", about: "Test details and a screenshot go out, then the run waits for a verification code.", ports: ["run.event", "account.fields", "screen.shot", "log.line", "code.in"] },
  { id: "image", title: "An image from the PC", about: "The run waits for an image, like a profile photo.", ports: ["run.event", "file.in", "log.line"] },
  { id: "value", title: "A value from a plugin", about: "The run waits for a value, like a caption.", ports: ["run.event", "value.in"] },
];

export interface Overview {
  contract: string;
  keysPersistent: boolean;
  plugins: Plugin[];
  catalog: CatalogPort[];
  extensions: string[];
  activity: Activity[];
  today: { messages: number; failures: number };
  /** Ports that need a choice everywhere (two answerers, or the chosen plugin isn't live). */
  conflicts: string[];
}

export interface Preview {
  endpoint: string;
  remote: boolean;
  problems: string[];
  alreadyAdded: string | null;
  manifest: { name?: string; title?: string; description?: string; version?: string };
  serves: ServedPort[];
  endpointDiffers: boolean;
}

export interface KeyCard {
  value: string;
  powershell: string;
  bash: string;
  cmd: string;
}

// ---------------------------------------------------------------------------------------------------- parsing

const STATUSES: PluginStatus[] = ["active", "waiting_key", "failing_checks", "unreachable", "paused", "needs_review", "key_lost"];
const str = (v: unknown, fallback = ""): string => (typeof v === "string" ? v : fallback);
const num = (v: unknown): number | null => (typeof v === "number" && Number.isFinite(v) ? v : null);
const arr = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);
const obj = (v: unknown): Record<string, unknown> => (v && typeof v === "object" && !Array.isArray(v) ? (v as Record<string, unknown>) : {});
const way = (v: unknown): PortWay => (v === "in" ? "in" : "out");
const sensitivity = (v: unknown): Sensitivity => (v === "public" || v === "secret" ? v : "personal");

export function parseServed(raw: unknown): ServedPort {
  const o = obj(raw);
  return { port: str(o.port), way: way(o.way), sensitivity: sensitivity(o.sensitivity), summary: str(o.summary),
    extension: o.extension === true, allowed: o.allowed === true };
}

export function parsePlugin(raw: unknown): Plugin {
  const o = obj(raw);
  const checks = o.checks == null ? null : obj(o.checks);
  const pending = o.pending == null ? null : obj(o.pending);
  return {
    name: str(o.name),
    title: str(o.title) || str(o.name),
    description: str(o.description),
    version: str(o.version),
    endpoint: str(o.endpoint),
    remote: o.remote === true,
    status: STATUSES.includes(o.status as PluginStatus) ? (o.status as PluginStatus) : "waiting_key",
    paused: o.paused === true,
    serves: arr(o.serves).map(parseServed).filter((s) => s.port),
    features: arr(o.features).filter((f): f is string => typeof f === "string"),
    kid: str(o.kid),
    health: o.health === "ok" || o.health === "failing" ? o.health : "unknown",
    healthDetail: str(o.healthDetail),
    seenAt: num(o.seenAt),
    checkedAt: num(o.checkedAt),
    checks: checks && {
      passed: checks.passed === true,
      total: num(checks.total) ?? 0,
      failed: num(checks.failed) ?? 0,
      items: arr(checks.items).map((c) => {
        const i = obj(c);
        return { name: str(i.name), ok: i.ok === true, required: i.required !== false, detail: str(i.detail), hint: str(i.hint),
          cause: i.cause === "key" ? "key" : "" };
      }),
    },
    pending: pending && {
      version: str(pending.version),
      added: arr(pending.added).map((p) => str(p)).filter(Boolean),
      removed: arr(pending.removed).map((p) => str(p)).filter(Boolean),
      needsPersonal: pending.needsPersonal === true,
      featuresAdded: arr(pending.featuresAdded).map((p) => str(p)).filter(Boolean),
      endpointChanged: pending.endpointChanged === true,
    },
    createdAt: num(o.createdAt) ?? 0,
  };
}

export function parseActivity(raw: unknown): Activity {
  const o = obj(raw);
  return { id: num(o.id) ?? 0, at: num(o.at) ?? 0, plugin: str(o.plugin), kind: str(o.kind), port: str(o.port) || null,
    runId: str(o.runId) || null, status: num(o.status), ok: o.ok === true, latencyMs: num(o.latencyMs), detail: str(o.detail) };
}

export function parseOverview(raw: unknown): Overview {
  const o = obj(raw);
  const today = obj(o.today);
  return {
    contract: str(o.contract, "cyclone.ports/1"),
    keysPersistent: o.keysPersistent !== false,
    plugins: arr(o.plugins).map(parsePlugin).filter((p) => p.name),
    catalog: arr(o.catalog).map((c) => {
      const i = obj(c);
      return { port: str(i.port), way: way(i.way), sensitivity: sensitivity(i.sensitivity), summary: str(i.summary),
        pluginServed: i.pluginServed !== false, servedBy: arr(i.servedBy).map((p) => str(p)).filter(Boolean) };
    }).filter((c) => c.port),
    extensions: arr(o.extensions).map((p) => str(p)).filter(Boolean),
    activity: arr(o.activity).map(parseActivity),
    today: { messages: num(today.messages) ?? 0, failures: num(today.failures) ?? 0 },
    conflicts: arr(o.conflicts).map((p) => str(p)).filter(Boolean),
  };
}

const BINDING_STATES: BindingState[] = ["ok", "empty", "conflict", "off", "unavailable", "vault"];
const bindingState = (v: unknown): BindingState => (BINDING_STATES.includes(v as BindingState) ? (v as BindingState) : "empty");
const names = (v: unknown): string[] => arr(v).map((p) => str(p)).filter(Boolean);

export function parseBindings(raw: unknown): BindingsView {
  const o = obj(raw);
  return {
    scope: str(o.scope, "default"),
    scopes: arr(o.scopes).map((s) => ({ scope: str(obj(s).scope), choices: num(obj(s).choices) ?? 0 })).filter((s) => s.scope),
    ports: arr(o.ports).map((r) => {
      const i = obj(r);
      const inherits = i.inherits == null ? null : obj(i.inherits);
      return {
        port: str(i.port), way: way(i.way), sensitivity: sensitivity(i.sensitivity), pluginServed: i.pluginServed !== false,
        extension: i.extension === true,
        candidates: arr(i.candidates).map((c) => {
          const x = obj(c);
          return { name: str(x.name), title: str(x.title) || str(x.name), live: x.live === true,
            status: STATUSES.includes(x.status as PluginStatus) ? (x.status as PluginStatus) : "waiting_key" };
        }).filter((c) => c.name),
        state: bindingState(i.state), effective: names(i.effective), chosen: i.chosen == null ? null : names(i.chosen),
        source: str(i.source, "automatic"), override: i.override === true,
        inherits: inherits && { state: bindingState(inherits.state), effective: names(inherits.effective), source: str(inherits.source) },
      };
    }).filter((r) => r.port),
    plugins: arr(o.plugins).map((p) => {
      const x = obj(p);
      return { name: str(x.name), title: str(x.title) || str(x.name),
        status: STATUSES.includes(x.status as PluginStatus) ? (x.status as PluginStatus) : "waiting_key" };
    }).filter((p) => p.name),
  };
}

const WAIT_STATES = ["waiting", "delivered", "timed_out", "cancelled", "failed"] as const;
const STEP_STATES: StepState[] = ["next", "now", "ok", "bad", "skipped"];
const TEST_STATES = ["running", "done", "needs_you", "stopped"] as const;

export function parseRun(raw: unknown): PortRun {
  const o = obj(raw);
  return { runId: str(o.runId), firstAt: num(o.firstAt) ?? 0, lastAt: num(o.lastAt) ?? 0, messages: num(o.messages) ?? 0, failures: num(o.failures) ?? 0 };
}

export function parseWait(raw: unknown): PortWait {
  const o = obj(raw);
  return {
    awaitId: str(o.awaitId), port: str(o.port), plugin: str(o.plugin),
    state: (WAIT_STATES as readonly string[]).includes(o.state as string) ? (o.state as PortWait["state"]) : "failed",
    timeoutAt: num(o.timeoutAt) ?? 0, createdAt: num(o.createdAt) ?? 0, result: o.result == null ? null : obj(o.result),
  };
}

export function parseTestRun(raw: unknown): TestRun {
  const o = obj(raw);
  return {
    runId: str(o.runId), scenario: str(o.scenario), title: str(o.title) || str(o.scenario),
    state: (TEST_STATES as readonly string[]).includes(o.state as string) ? (o.state as TestRun["state"]) : "running",
    routine: str(o.routine) || null, app: str(o.app) || null, startedAt: num(o.startedAt) ?? 0, finishedAt: num(o.finishedAt),
    steps: arr(o.steps).map((s) => {
      const x = obj(s);
      return { kind: x.kind === "await" ? "await" : "emit", port: str(x.port),
        state: STEP_STATES.includes(x.state as StepState) ? (x.state as StepState) : "next", detail: str(x.detail),
        label: str(x.label) };
    }) as TestRun["steps"],
  };
}

function parseKey(raw: unknown): KeyCard {
  const o = obj(raw);
  return { value: str(o.value), powershell: str(o.powershell), bash: str(o.bash), cmd: str(o.cmd) };
}

// ---------------------------------------------------------------------------------------------------- client

type PluginAnswer = { plugin: Plugin; activity: Activity[] };
const pluginAnswer = (raw: unknown): PluginAnswer => {
  const o = obj(raw);
  return { plugin: parsePlugin(o.plugin), activity: arr(o.activity).map(parseActivity) };
};
const path = (name: string, action = "") => `/v1/ports/plugins/${encodeURIComponent(name)}${action}`;

export const ports = {
  async overview(client: GatewayClient): Promise<Overview> {
    return parseOverview(await client.get("/v1/ports/overview"));
  },
  async preview(client: GatewayClient, endpoint: string): Promise<Preview> {
    const o = obj(await client.post("/v1/ports/plugins/preview", { endpoint }));
    return {
      endpoint: str(o.endpoint),
      remote: o.remote === true,
      problems: arr(o.problems).map((p) => str(p)).filter(Boolean),
      alreadyAdded: str(o.alreadyAdded) || null,
      manifest: obj(o.manifest) as Preview["manifest"],
      serves: arr(o.serves).map(parseServed).filter((s) => s.port),
      endpointDiffers: o.endpointDiffers === true,
    };
  },
  async add(client: GatewayClient, endpoint: string, allowed: string[]): Promise<{ plugin: Plugin; key: KeyCard }> {
    const o = obj(await client.post("/v1/ports/plugins", { endpoint, allowed }));
    return { plugin: parsePlugin(o.plugin), key: parseKey(o.key) };
  },
  async plugin(client: GatewayClient, name: string): Promise<PluginAnswer> {
    return pluginAnswer(await client.get(path(name)));
  },
  async check(client: GatewayClient, name: string): Promise<PluginAnswer> {
    return pluginAnswer(await client.post(path(name, "/check")));
  },
  async setPort(client: GatewayClient, name: string, port: string, allowed: boolean): Promise<PluginAnswer> {
    return pluginAnswer(await client.post(path(name, "/ports"), { port, allowed }));
  },
  async pause(client: GatewayClient, name: string, paused: boolean): Promise<PluginAnswer> {
    return pluginAnswer(await client.post(path(name, "/pause"), { paused }));
  },
  async test(client: GatewayClient, name: string): Promise<PluginAnswer & { ok: boolean; port: string; latencyMs: number | null; status: number | null }> {
    const raw = await client.post(path(name, "/test"));
    const o = obj(raw);
    return { ...pluginAnswer(raw), ok: o.ok === true, port: str(o.port), latencyMs: num(o.latencyMs), status: num(o.status) };
  },
  async newKey(client: GatewayClient, name: string): Promise<{ plugin: Plugin; key: KeyCard }> {
    const o = obj(await client.post(path(name, "/key")));
    return { plugin: parsePlugin(o.plugin), key: parseKey(o.key) };
  },
  async approve(client: GatewayClient, name: string): Promise<PluginAnswer> {
    return pluginAnswer(await client.post(path(name, "/approve")));
  },
  async remove(client: GatewayClient, name: string): Promise<void> {
    await client.post(path(name, "/delete"));
  },
  async runs(client: GatewayClient): Promise<{ runs: PortRun[]; testRuns: TestRun[] }> {
    const o = obj(await client.get("/v1/ports/runs"));
    return { runs: arr(o.runs).map(parseRun).filter((r) => r.runId), testRuns: arr(o.testRuns).map(parseTestRun).filter((r) => r.runId) };
  },
  async run(client: GatewayClient, runId: string): Promise<{ activity: Activity[]; waits: PortWait[] }> {
    const o = obj(await client.get(`/v1/ports/runs/${encodeURIComponent(runId)}`));
    return { activity: arr(o.activity).map(parseActivity), waits: arr(o.waits).map(parseWait) };
  },
  async activity(client: GatewayClient, filter: { plugin?: string; port?: string; status?: "" | "ok" | "failed" } = {}): Promise<Activity[]> {
    const query = new URLSearchParams();
    for (const [k, v] of Object.entries(filter)) if (v) query.set(k, v);
    query.set("limit", "150");
    return arr(obj(await client.get(`/v1/ports/activity?${query}`)).activity).map(parseActivity);
  },
  async startTestRun(client: GatewayClient, scenario: string, scope: { routine?: string; app?: string } = {}): Promise<TestRun> {
    return parseTestRun(await client.post("/v1/ports/test-runs", { scenario, ...scope }));
  },
  async testRun(client: GatewayClient, runId: string): Promise<TestRun> {
    return parseTestRun(await client.get(`/v1/ports/test-runs/${encodeURIComponent(runId)}`));
  },
  async stopTestRun(client: GatewayClient, runId: string): Promise<TestRun> {
    return parseTestRun(await client.post(`/v1/ports/test-runs/${encodeURIComponent(runId)}/stop`));
  },
  async bindings(client: GatewayClient, scope = "default"): Promise<BindingsView> {
    return parseBindings(await client.get(`/v1/ports/bindings?scope=${encodeURIComponent(scope)}`));
  },
  /** plugins: null for automatic (or inherit, outside "everywhere"), [] for off. */
  async setBinding(client: GatewayClient, scope: string, port: string, plugins: string[] | null): Promise<BindingsView> {
    return parseBindings(await client.post("/v1/ports/bindings", { scope, port, plugins }));
  },
};

// ---------------------------------------------------------------------------------------------------- words

export type Tone = "neutral" | "accent" | "success" | "warning" | "danger";

export interface StatusInfo {
  label: string;
  tone: Tone;
  /** One sentence: what is going on. */
  explain: string;
  /** The one action that moves it forward, if any. */
  action: "checks" | "review" | "resume" | "key" | null;
  actionLabel: string;
}

export function statusInfo(plugin: Pick<Plugin, "status" | "healthDetail" | "checks">): StatusInfo {
  switch (plugin.status) {
    case "active":
      return { label: "Live", tone: "success", explain: "Passed its checks and answers. Runs can use its ports.", action: null, actionLabel: "" };
    case "waiting_key":
      return { label: "Waiting for its key", tone: "accent", explain: "Give the plugin its key and restart it, then run the checks.", action: "checks", actionLabel: "Run checks" };
    case "failing_checks": {
      const failed = plugin.checks?.failed ?? 0;
      return { label: "Checks failed", tone: "danger", explain: `${failed} ${failed === 1 ? "check" : "checks"} failed. Each one says what to fix.`, action: "checks", actionLabel: "Run checks again" };
    }
    case "unreachable":
      return { label: "Can't reach it", tone: "danger", explain: plugin.healthDetail || "It stopped answering. Start the plugin again.", action: "checks", actionLabel: "Check again" };
    case "paused":
      return { label: "Paused", tone: "neutral", explain: "It gets nothing until you resume it.", action: "resume", actionLabel: "Resume" };
    case "needs_review":
      return { label: "Changed", tone: "warning", explain: "It asks for something new. It gets nothing until you review the change.", action: "review", actionLabel: "Review change" };
    case "key_lost":
      return { label: "Needs a new key", tone: "warning", explain: "This PC lost the plugin's key. Make a new one and give it to the plugin.", action: "key", actionLabel: "Make a new key" };
  }
}

const PORT_NAMES: Record<string, string> = {
  "run.event": "Run events",
  "log.line": "Notes",
  "screen.shot": "Screenshots",
  "account.fields": "Account details",
  "page.text": "Page text",
  "file.out": "Files from runs",
  "file.in": "Files for the phone",
  "value.in": "Values",
  "code.in": "Verification codes",
  "link.in": "Confirmation links",
  "secret.out": "Passwords and keys out",
  "secret.in": "Passwords and keys in",
};

const PORT_ABOUT: Record<string, string> = {
  "run.event": "When a run starts, moves to a page, needs you, finishes or fails.",
  "log.line": "Short notes a run writes along the way.",
  "screen.shot": "A picture of the screen, through a link that works once.",
  "account.fields": "Name, birthday, email, username. Never a password.",
  "page.text": "The words on the page, with anything secret hidden.",
  "file.out": "A file a run made or downloaded.",
  "file.in": "An image or PDF, saved in the run's folder on the phone.",
  "value.in": "Text the run asked for, like a caption or an address.",
  "code.in": "A code from your own phone or inbox, typed in without anyone seeing it.",
  "link.in": "A confirmation link from your own inbox, opened in the run.",
  "secret.out": "A new password or key, sealed straight into your vault.",
  "secret.in": "A vault item you release for one run.",
};

/** What a port is for, in the owner's words (the catalog's own summary for ports Glass doesn't know yet). */
export function portAbout(port: string, fallback = ""): string {
  return PORT_ABOUT[port] ?? fallback;
}

/** "Screenshots", or for an extension port x.crm.next-handle: "Next handle". */
export function portLabel(port: string): string {
  if (PORT_NAMES[port]) return PORT_NAMES[port];
  const last = port.split(".").pop() ?? port;
  const words = last.replace(/-/g, " ");
  return words.charAt(0).toUpperCase() + words.slice(1);
}

export function wayLabel(w: PortWay): string {
  return w === "out" ? "Phone → plugin" : "Plugin → phone";
}

export function sensitivityLabel(s: Sensitivity): string {
  return s === "public" ? "Public" : s === "personal" ? "Personal" : "Secret";
}

/** Initials and one of six token-defined tile colours, stable per plugin name. */
export function monogram(name: string, title = name): { letters: string; tile: number } {
  const words = (title || name).replace(/[^A-Za-z0-9 -]/g, " ").split(/[\s-]+/).filter(Boolean);
  const letters = (words.length > 1 ? words[0][0] + words[1][0] : (words[0] ?? "?").slice(0, 2)).toUpperCase();
  let hash = 2166136261; // FNV-1a: spreads similar names over the six tiles
  for (const char of name) hash = Math.imul(hash ^ char.charCodeAt(0), 16777619) >>> 0;
  return { letters, tile: ((hash >>> 3) % 6) + 1 };
}

/** A conformance check in plain words: "file.in: accepts a cancel (2xx)" → "Files for the phone: accepts a cancel". */
export function checkLabel(name: string): string {
  let text = name.replace(/\s*\((?:2xx|4xx|401|404)\)\s*$/, "");
  const scoped = text.match(/^([a-z][a-z0-9-]*(?:\.[a-z0-9-]+)+): (.*)$/);
  if (scoped) text = `${portLabel(scoped[1])}: ${scoped[2]}`;
  else if (/^[a-z]/.test(text)) text = text.charAt(0).toUpperCase() + text.slice(1);
  return text;
}

export function activityText(a: Activity): string {
  const port = a.port ? ` · ${portLabel(a.port)}` : "";
  switch (a.kind) {
    case "added": return `Added (${a.detail})`;
    case "check": return a.ok ? "Passed its checks" : a.detail.charAt(0).toUpperCase() + a.detail.slice(1);
    case "test": return (a.ok ? "Test message delivered" : `Test message failed: ${a.detail}`) + port;
    case "consent": return `${a.port ? portLabel(a.port) : "A port"} ${a.detail}`;
    case "paused": return "Paused";
    case "resumed": return "Resumed";
    case "key": return "New key made";
    case "removed": return "Removed";
    case "drift": return "Changed what it asks for";
    case "approved": return "Change approved";
    case "version": return `Updated: ${a.detail}`;
    case "health": return a.ok ? "Answering again" : `Stopped answering: ${a.detail}`;
    case "emit": return a.plugin === "ports" ? `Not sent${port}: ${a.detail}` : (a.ok ? "Delivered" : "Not delivered") + port + (a.ok || !a.detail ? "" : `: ${a.detail}`);
    case "binding": return `${a.port ? portLabel(a.port) : "A port"}: ${a.detail}`;
    case "await": return (a.ok ? "Asked" : "Couldn't ask") + port + (a.ok ? "" : `: ${a.detail}`);
    case "deliver": return `Answered${port}: ${a.detail}`;
    case "timeout": return `Nothing came${port}`;
    case "cancel": return `Wait cancelled${port}`;
    case "run": return a.detail.charAt(0).toUpperCase() + a.detail.slice(1);
    default: return a.detail || a.kind;
  }
}

/** What a binding state means, for the Port map. */
export function bindingInfo(row: Pick<BindingRow, "state" | "way" | "effective" | "candidates">): { label: string; tone: Tone; explain: string } {
  const n = row.effective.length;
  switch (row.state) {
    case "ok":
      return row.way === "out"
        ? { label: n === 1 ? "1 plugin" : `${n} plugins`, tone: "success", explain: n === 1 ? "Goes to one plugin." : `Goes to all ${n}.` }
        : { label: "Answered", tone: "success", explain: "One plugin answers a waiting run." };
    case "empty":
      return { label: "No plugin", tone: "neutral", explain: row.candidates.length ? "Its plugins aren't live yet." : "No plugin serves it yet." };
    case "conflict":
      return { label: "Choose one", tone: "warning", explain: `${row.candidates.filter((c) => c.live).length} plugins could answer. A waiting run takes one answer, so choose which.` };
    case "off":
      return { label: "Off", tone: "neutral", explain: "Switched off here. Runs send nothing and wait on nothing." };
    case "unavailable":
      return { label: "Unavailable", tone: "danger", explain: "The chosen plugin isn't live, so nothing gets through. Bring it back or choose another." };
    case "vault":
      return { label: "Vault only", tone: "neutral", explain: "Handled by the hub and your vault, never a plugin." };
  }
}

/** "Everywhere", "Routine: Daily post", "App: com.instagram.android". */
export function scopeLabel(scope: string, routines: Map<string, string> = new Map()): string {
  if (scope === "default") return "Everywhere";
  if (scope.startsWith("routine:")) return `Routine: ${routines.get(scope.slice(8)) ?? scope.slice(8)}`;
  if (scope.startsWith("app:")) return `App: ${scope.slice(4)}`;
  return scope;
}

/** The kit's example plugins, offered when the owner has none yet. */
export const STARTERS: Array<{ title: string; endpoint: string; about: string; ports: string[] }> = [
  { title: "Run logger", endpoint: "http://127.0.0.1:8771", about: "Keeps every run event, note and screenshot in a folder.", ports: ["run.event", "log.line", "screen.shot"] },
  { title: "PC images", endpoint: "http://127.0.0.1:8772", about: "Sends the newest image in a PC folder to the phone.", ports: ["file.in"] },
  { title: "SMS codes", endpoint: "http://127.0.0.1:8773", about: "Delivers verification codes from your other phone.", ports: ["code.in"] },
];
