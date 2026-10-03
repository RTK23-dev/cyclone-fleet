/**
 * The Command Center's AI (plan 33 §7). The runtime holds the provider key, calls the models and runs the tools;
 * Glass only shows the conversation, the settings and the proposals, and sends the owner's words and decisions.
 * Answers are Markdown-like text that this module turns into typed pieces, so nothing is ever injected as markup.
 */
import type { GatewayClient } from "./gateway.js";
import type { RefKind } from "./pages.js";

export type Autonomy = "propose" | "workspace";
export type ToolKind = "read" | "workspace" | "phone";

export interface AiStatus {
  provider: { name: string; site: string; keysUrl: string; privacyUrl: string };
  keySaved: boolean;
  keySavedAt: number | null;
  /** False when the key is kept in memory only (until Cyclone restarts). */
  keyKept: boolean;
  model: string | null;
  dailyCapUsd: number;
  monthlyCapUsd: number;
  privateOnly: boolean;
  autonomy: Autonomy;
  instructions: string;
  spentTodayUsd: number;
  spentMonthUsd: number;
  callsToday: number;
  tools: Array<{ name: string; kind: ToolKind; description: string }>;
  check?: { ok: boolean; usedUsd?: number; limitUsd?: number; remainingUsd?: number; freeTier?: boolean };
}

export interface AiModel {
  id: string;
  name: string;
  contextLength: number | null;
  promptPerM: number | null;
  completionPerM: number | null;
  tools: boolean;
  free: boolean;
}

export interface Activity {
  label: string;
  outcome: "done" | "proposed" | "error";
  proposalId: string | null;
  error: string | null;
}

export interface AiMessage {
  seq: number;
  role: "user" | "assistant" | "note";
  text: string;
  at: number;
  model?: string | null;
  costUsd?: number | null;
  activity: Activity[];
}

export interface Proposal {
  id: string;
  conversationId: string;
  tool: string;
  kind: ToolKind;
  summary: string;
  preview: string;
  state: "open" | "applied" | "discarded" | "failed";
  result: Record<string, unknown> | null;
  createdAt: number;
  decidedAt: number | null;
}

export interface ConversationMeta {
  id: string;
  title: string;
  pageId: string | null;
  model: string | null;
  state: "idle" | "working" | "failed";
  detail: string;
  createdAt: number;
  updatedAt: number;
  openProposals: number;
}

export interface Conversation extends ConversationMeta {
  costUsd: number;
  messages: AiMessage[];
  proposals: Proposal[];
}

// ------------------------------------------------------------------------------------------------ parsers

const str = (v: unknown, f = ""): string => (typeof v === "string" ? v : f);
const num = (v: unknown, f = 0): number => (typeof v === "number" && Number.isFinite(v) ? v : f);
const numOrNull = (v: unknown): number | null => (typeof v === "number" && Number.isFinite(v) ? v : null);
const strOrNull = (v: unknown): string | null => (typeof v === "string" && v ? v : null);
const list = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);
const obj = (v: unknown): Record<string, unknown> => (v && typeof v === "object" && !Array.isArray(v) ? (v as Record<string, unknown>) : {});
const oneOf = <T extends string>(v: unknown, allowed: readonly T[], f: T): T => (allowed.includes(v as T) ? (v as T) : f);
const safeUrl = (v: unknown): string => (typeof v === "string" && /^https:\/\/[^\s"'<>]+$/.test(v) ? v : "");

export function parseStatus(raw: unknown): AiStatus {
  const r = obj(raw);
  const p = obj(r.provider);
  const check = r.check === undefined ? undefined : obj(r.check);
  return {
    provider: { name: str(p.name, "your AI provider"), site: safeUrl(p.site), keysUrl: safeUrl(p.keysUrl), privacyUrl: safeUrl(p.privacyUrl) },
    keySaved: r.keySaved === true,
    keySavedAt: numOrNull(r.keySavedAt),
    keyKept: r.keyKept !== false,
    model: strOrNull(r.model),
    dailyCapUsd: num(r.dailyCapUsd, 2),
    monthlyCapUsd: num(r.monthlyCapUsd, 30),
    privateOnly: r.privateOnly !== false,
    autonomy: oneOf(r.autonomy, ["propose", "workspace"] as const, "propose"),
    instructions: str(r.instructions),
    spentTodayUsd: num(r.spentTodayUsd),
    spentMonthUsd: num(r.spentMonthUsd),
    callsToday: num(r.callsToday),
    tools: list(r.tools).map((t) => {
      const o = obj(t);
      return { name: str(o.name), kind: oneOf(o.kind, ["read", "workspace", "phone"] as const, "read"), description: str(o.description) };
    }).filter((t) => t.name),
    ...(check ? { check: { ok: check.ok === true, usedUsd: numOrNull(check.usedUsd) ?? undefined, limitUsd: numOrNull(check.limitUsd) ?? undefined,
      remainingUsd: numOrNull(check.remainingUsd) ?? undefined, freeTier: typeof check.freeTier === "boolean" ? check.freeTier : undefined } } : {}),
  };
}

export function parseModel(raw: unknown): AiModel | null {
  const r = obj(raw);
  if (!str(r.id)) return null;
  return { id: str(r.id), name: str(r.name, str(r.id)), contextLength: numOrNull(r.contextLength), promptPerM: numOrNull(r.promptPerM),
    completionPerM: numOrNull(r.completionPerM), tools: r.tools === true, free: r.free === true };
}

function parseMessage(raw: unknown): AiMessage | null {
  const r = obj(raw);
  const role = oneOf(r.role, ["user", "assistant", "note"] as const, "note");
  if (!("role" in r)) return null;
  return {
    seq: num(r.seq), role, text: str(r.text), at: num(r.at), model: strOrNull(r.model), costUsd: numOrNull(r.costUsd),
    activity: list(r.activity).map((a) => {
      const o = obj(a);
      return { label: str(o.label), outcome: oneOf(o.outcome, ["done", "proposed", "error"] as const, "done"), proposalId: strOrNull(o.proposalId), error: strOrNull(o.error) };
    }),
  };
}

export function parseProposal(raw: unknown): Proposal | null {
  const r = obj(raw);
  if (!str(r.id)) return null;
  return {
    id: str(r.id), conversationId: str(r.conversationId), tool: str(r.tool), kind: oneOf(r.kind, ["read", "workspace", "phone"] as const, "phone"),
    summary: str(r.summary), preview: str(r.preview), state: oneOf(r.state, ["open", "applied", "discarded", "failed"] as const, "open"),
    result: r.result && typeof r.result === "object" ? (r.result as Record<string, unknown>) : null, createdAt: num(r.createdAt), decidedAt: numOrNull(r.decidedAt),
  };
}

export function parseMeta(raw: unknown): ConversationMeta {
  const r = obj(raw);
  return {
    id: str(r.id), title: str(r.title, "Conversation"), pageId: strOrNull(r.pageId), model: strOrNull(r.model),
    state: oneOf(r.state, ["idle", "working", "failed"] as const, "idle"), detail: str(r.detail), createdAt: num(r.createdAt),
    updatedAt: num(r.updatedAt), openProposals: num(r.openProposals),
  };
}

export function parseConversation(raw: unknown): Conversation {
  const r = obj(raw);
  return {
    ...parseMeta(raw), costUsd: num(r.costUsd),
    messages: list(r.messages).map(parseMessage).filter((m): m is AiMessage => m !== null),
    proposals: list(r.proposals).map(parseProposal).filter((p): p is Proposal => p !== null),
  };
}

// ------------------------------------------------------------------------------------------------ the API

const enc = encodeURIComponent;

export const aiApi = {
  status: async (client: GatewayClient) => parseStatus(await client.get("/v1/cc/ai")),
  saveSettings: async (client: GatewayClient, body: Partial<Pick<AiStatus, "model" | "dailyCapUsd" | "monthlyCapUsd" | "privateOnly" | "autonomy" | "instructions">>) =>
    parseStatus(await client.post("/v1/cc/ai/settings", body)),
  /** The provider key goes to this PC's runtime once; it never comes back. */
  saveKey: async (client: GatewayClient, key: string) => parseStatus(await client.post("/v1/cc/ai/key", { key })),
  testKey: async (client: GatewayClient) => parseStatus(await client.post("/v1/cc/ai/key/test")),
  forgetKey: async (client: GatewayClient) => parseStatus(await client.post("/v1/cc/ai/key/forget")),
  models: async (client: GatewayClient, options: { all?: boolean; refresh?: boolean } = {}) => {
    const query = [options.all ? "all=true" : "", options.refresh ? "refresh=true" : ""].filter(Boolean).join("&");
    const r = obj(await client.get(`/v1/cc/ai/models${query ? `?${query}` : ""}`));
    return { models: list(r.models).map(parseModel).filter((m): m is AiModel => m !== null), total: num(r.total) };
  },
  conversations: async (client: GatewayClient) => {
    const r = obj(await client.get("/v1/cc/ai/conversations"));
    return { conversations: list(r.conversations).map(parseMeta), proposals: list(r.proposals).map(parseProposal).filter((p): p is Proposal => p !== null) };
  },
  create: async (client: GatewayClient, body: { pageId?: string; model?: string } = {}) => parseConversation(await client.post("/v1/cc/ai/conversations", body)),
  get: async (client: GatewayClient, id: string) => parseConversation(await client.get(`/v1/cc/ai/conversations/${enc(id)}`)),
  update: async (client: GatewayClient, id: string, body: { model?: string | null; title?: string }) =>
    parseConversation(await client.post(`/v1/cc/ai/conversations/${enc(id)}`, body)),
  send: async (client: GatewayClient, id: string, text: string) => parseConversation(await client.post(`/v1/cc/ai/conversations/${enc(id)}/messages`, { text })),
  stop: async (client: GatewayClient, id: string) => parseConversation(await client.post(`/v1/cc/ai/conversations/${enc(id)}/stop`)),
  remove: (client: GatewayClient, id: string) => client.post(`/v1/cc/ai/conversations/${enc(id)}/delete`),
  apply: async (client: GatewayClient, id: string) => parseProposal(await client.post(`/v1/cc/ai/proposals/${enc(id)}/apply`)),
  discard: async (client: GatewayClient, id: string) => parseProposal(await client.post(`/v1/cc/ai/proposals/${enc(id)}/discard`)),
};

// ------------------------------------------------------------------------------------------------ pure helpers

export function formatUsd(value: number): string {
  if (value === 0) return "$0";
  if (value < 0.01) return `$${value.toFixed(4).replace(/0+$/, "")}`;
  return `$${value.toFixed(2)}`;
}

/** "$3 in · $15 out per million tokens", or "Free". */
export function priceLabel(model: AiModel): string {
  if (model.free) return "Free";
  if (model.promptPerM === null || model.completionPerM === null) return "Price varies";
  const n = (v: number) => (v >= 10 ? v.toFixed(0) : v >= 1 ? v.toFixed(2).replace(/\.?0+$/, "") : v.toFixed(3).replace(/\.?0+$/, ""));
  return `$${n(model.promptPerM)} in · $${n(model.completionPerM)} out per M tokens`;
}

export function contextLabel(model: AiModel): string {
  if (!model.contextLength) return "";
  return model.contextLength >= 1_000_000 ? `${(model.contextLength / 1_000_000).toFixed(1).replace(/\.0$/, "")}M context`
    : `${Math.round(model.contextLength / 1000)}K context`;
}

/** The provider part of a model id ("acme/planner" → "acme"), for grouping. */
export const makerOf = (model: AiModel): string => model.id.split("/")[0] ?? "";

export function filterModels(models: AiModel[], query: string, options: { freeOnly?: boolean } = {}): AiModel[] {
  const words = query.toLowerCase().split(/\s+/).filter(Boolean);
  return models.filter((m) => (!options.freeOnly || m.free) && words.every((w) => m.name.toLowerCase().includes(w) || m.id.toLowerCase().includes(w)));
}

/** Share of a budget used, 0..1, for the spending bars. */
export const budgetShare = (spent: number, cap: number): number => (cap > 0 ? Math.max(0, Math.min(1, spent / cap)) : 0);

// ------------------------------------------------------------------------------------------------ answers as typed pieces

export type AnswerSpan = { t: string; b?: true; i?: true; c?: true; s?: true } | { mention: { kind: RefKind; id: string; label: string } };
export type AnswerLineType = "p" | "h1" | "h2" | "h3" | "bullet" | "number" | "todo" | "done" | "quote" | "callout" | "divider" | "code";
export interface AnswerLine {
  type: AnswerLineType;
  spans: AnswerSpan[];
}

const MENTION_KINDS = ["page", "device", "routine", "task", "account", "connection"] as const;
const INLINE = /\*\*(.+?)\*\*|~~(.+?)~~|`([^`]+)`|(?<![\w*])\*([^*\s](?:[^*]*[^*\s])?)\*(?![\w*])|@\[(page|device|routine|task|account|connection):([^\]|\s]{1,120})(?:\|([^\]]{0,120}))?\]/g;

export function answerSpans(text: string): AnswerSpan[] {
  const out: AnswerSpan[] = [];
  let at = 0;
  for (const m of text.matchAll(INLINE)) {
    const index = m.index ?? 0;
    if (index > at) out.push({ t: text.slice(at, index) });
    at = index + m[0].length;
    if (m[5]) {
      const kind = m[5] as (typeof MENTION_KINDS)[number];
      out.push({ mention: { kind, id: m[6] ?? "", label: (m[7] ?? "").trim() || (m[6] ?? "") } });
    } else if (m[1] !== undefined) out.push({ t: m[1], b: true });
    else if (m[2] !== undefined) out.push({ t: m[2], s: true });
    else if (m[3] !== undefined) out.push({ t: m[3], c: true });
    else if (m[4] !== undefined) out.push({ t: m[4], i: true });
  }
  if (at < text.length) out.push({ t: text.slice(at) });
  return out.filter((s) => "mention" in s || s.t);
}

const LINE_RULES: Array<[RegExp, AnswerLineType]> = [
  [/^###\s+(.*)$/, "h3"], [/^##\s+(.*)$/, "h2"], [/^#\s+(.*)$/, "h1"],
  [/^[-*]\s+\[\s\]\s+(.*)$/, "todo"], [/^[-*]\s+\[[xX]\]\s+(.*)$/, "done"],
  [/^[-*•]\s+(.*)$/, "bullet"], [/^\d{1,3}[.)]\s+(.*)$/, "number"], [/^>\s?(.*)$/, "quote"], [/^!\s+(.*)$/, "callout"],
];

/** Markdown-like text (an answer, or a proposal's preview) as lines of typed pieces. */
export function parseAnswer(text: string): AnswerLine[] {
  const lines: AnswerLine[] = [];
  let fenced = false;
  for (const raw of text.replace(/\r\n/g, "\n").split("\n")) {
    const line = raw.replace(/\s+$/, "");
    if (line.trim().startsWith("```")) {
      fenced = !fenced;
      continue;
    }
    if (fenced) {
      lines.push({ type: "code", spans: [{ t: line || " " }] });
      continue;
    }
    const trimmed = line.trim();
    if (!trimmed) continue;
    if (/^(?:-{3,}|\*{3,}|_{3,})$/.test(trimmed)) {
      lines.push({ type: "divider", spans: [] });
      continue;
    }
    const rule = LINE_RULES.find(([re]) => re.test(trimmed));
    if (rule) lines.push({ type: rule[1], spans: answerSpans(trimmed.replace(rule[0], "$1")) });
    else lines.push({ type: "p", spans: answerSpans(trimmed) });
  }
  return lines;
}

/** What the owner is asked before a proposal becomes real. */
export function proposalVerb(p: Proposal): string {
  return p.kind === "phone" ? (p.tool === "create_task" || p.tool === "run_routine" ? "Start it" : "Apply") : "Apply";
}

/** Prompts for an empty conversation, about a page or the whole workspace. */
export function suggestions(onPage: boolean): string[] {
  return onPage
    ? ["Summarise this page", "Turn this page's to-dos into plan cards", "Plan next week on this page", "What is missing from this plan?"]
    : ["Plan my week from my routines and tasks", "What failed today, and why?", "Make a page for a new project", "Which phones are free right now?"];
}
