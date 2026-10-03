import test from "node:test";
import assert from "node:assert/strict";
import { installMiniDom } from "./helpers/mini-dom.mjs";
import { fakeGateway, flush } from "./helpers/fakeGateway.mjs";
import { GatewayClient } from "../.test-dist/services/gateway.js";
import { parseRoute, routeHref } from "../.test-dist/core/router.js";
import {
  answerSpans, budgetShare, contextLabel, filterModels, formatUsd, parseAnswer, parseConversation, parseStatus, priceLabel,
} from "../.test-dist/services/ai.js";
import { filterSlash } from "../.test-dist/services/pages.js";
import { createAiPanel } from "../.test-dist/workspace/aiPanel.js";
import { createAiSettings } from "../.test-dist/workspace/aiSettings.js";
import { workspaceBus } from "../.test-dist/workspace/directory.js";

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const PROVIDER = { name: "OpenRouter", site: "https://openrouter.ai", keysUrl: "https://openrouter.ai/settings/keys", privacyUrl: "https://openrouter.ai/settings/privacy" };
const TOOLS = [{ name: "read_page", kind: "read", description: "One page as Markdown." },
  { name: "append_to_page", kind: "workspace", description: "Add blocks to a page." },
  { name: "create_task", kind: "phone", description: "Propose a task." }];
const MODELS = [
  { id: "acme/planner-large", name: "Acme: Planner Large", contextLength: 200000, promptPerM: 3, completionPerM: 15, tools: true, free: false },
  { id: "acme/tiny:free", name: "Acme: Tiny (free)", contextLength: 32000, promptPerM: 0, completionPerM: 0, tools: true, free: true },
];

function status(extra = {}) {
  return { provider: PROVIDER, keySaved: true, keySavedAt: Date.now() - 60_000, keyKept: true, model: "acme/planner-large", dailyCapUsd: 2, monthlyCapUsd: 30,
    privateOnly: true, autonomy: "propose", instructions: "", spentTodayUsd: 0.12, spentMonthUsd: 1.5, callsToday: 4, tools: TOOLS, ...extra };
}

function ctxWith(fetch) {
  const went = [];
  return { went, ctx: { client: new GatewayClient({ token: "t", fetch }), version: "1.0.0-alpha.36", devices: [], device: null, devicesError: null,
    navigate: (r) => went.push(r), selectDevice() {}, refreshDevices: async () => {} } };
}

// ------------------------------------------------------------------------------------------------ pure pieces

test("status, prices and answers parse defensively", () => {
  const s = parseStatus({ ...status(), provider: { name: "OpenRouter", keysUrl: "javascript:alert(1)" }, autonomy: "yolo", check: { ok: true, remainingUsd: 8.75 } });
  assert.equal(s.provider.name, "OpenRouter");
  assert.equal(s.provider.keysUrl, "", "only https links are kept");
  assert.equal(s.autonomy, "propose");
  assert.equal(s.check.remainingUsd, 8.75);
  assert.equal(parseStatus({}).keySaved, false);
  assert.equal(priceLabel(MODELS[0]), "$3 in · $15 out per M tokens");
  assert.equal(priceLabel(MODELS[1]), "Free");
  assert.equal(priceLabel({ ...MODELS[0], promptPerM: 0.15, completionPerM: 0.6 }), "$0.15 in · $0.6 out per M tokens");
  assert.equal(contextLabel(MODELS[0]), "200K context");
  assert.equal(contextLabel({ ...MODELS[0], contextLength: 1_048_576 }), "1M context");
  assert.equal(formatUsd(0.004), "$0.004");
  assert.equal(formatUsd(1.5), "$1.50");
  assert.equal(budgetShare(3, 2), 1);
  assert.deepEqual(filterModels(MODELS, "tiny").map((m) => m.id), ["acme/tiny:free"]);
  assert.deepEqual(filterModels(MODELS, "", { freeOnly: true }).map((m) => m.id), ["acme/tiny:free"]);
  const lines = parseAnswer("## Plan\n- Ask @[routine:rtn_answer001|Answer orders] **today**\n- [x] done\n1. first\n```\ncode\n```\n---\n<script>x</script>");
  assert.deepEqual(lines.map((l) => l.type), ["h2", "bullet", "done", "number", "code", "divider", "p"]);
  assert.deepEqual(lines[1].spans, [{ t: "Ask " }, { mention: { kind: "routine", id: "rtn_answer001", label: "Answer orders" } }, { t: " " }, { t: "today", b: true }]);
  assert.deepEqual(lines[6].spans, [{ t: "<script>x</script>" }], "markup stays text");
  assert.deepEqual(answerSpans("use `x` and *y*"), [{ t: "use " }, { t: "x", c: true }, { t: " and " }, { t: "y", i: true }]);
  const c = parseConversation({ id: "ai_abcdefgh", messages: [{ role: "assistant", seq: 2, text: "Hi", activity: [{ label: "Read", outcome: "weird" }] }, { nope: 1 }], proposals: [{ id: "" }] });
  assert.equal(c.messages.length, 1);
  assert.equal(c.messages[0].activity[0].outcome, "done");
  assert.deepEqual(c.proposals, []);
});

test("the AI has a route, and / Ask AI comes first in the block menu", () => {
  assert.deepEqual(parseRoute("#/command/ai"), { name: "command", tab: "ai" });
  assert.equal(routeHref({ name: "command", tab: "ai" }), "#/command/ai");
  assert.equal(filterSlash("")[0].id, "ai");
  assert.equal(filterSlash("ask")[0].action, "ai");
});

// ------------------------------------------------------------------------------------------------ settings

test("AI settings: the key is sent once and never shown, models are picked with prices, limits and choices save", async () => {
  installMiniDom();
  let current = status({ keySaved: false, keySavedAt: null, model: null });
  const posts = [];
  const gateway = fakeGateway({
    "GET /v1/cc/ai": () => current,
    "GET /v1/cc/ai/models": ({ query }) => ({ models: query.all ? [...MODELS, { ...MODELS[0], id: "acme/chat", name: "Acme: Chat only", tools: false }] : MODELS, total: 3 }),
    "POST /v1/cc/ai/key": ({ body }) => { posts.push(["key", body]); current = status({ model: null, check: { ok: true, remainingUsd: 8.75 } }); return current; },
    "POST /v1/cc/ai/settings": ({ body }) => { posts.push(["settings", body]); current = { ...current, ...body }; return current; },
    "POST /v1/cc/ai/key/forget": () => { current = status({ keySaved: false }); return current; },
  });
  const { ctx } = ctxWith(gateway.fetch);
  const page = createAiSettings(ctx);
  try {
    await flush();
    assert.match(page.element.textContent, /OpenRouter key/);
    assert.match(page.element.textContent, /No key yet/);
    assert.match(page.element.textContent, /Save a key first/);
    const field = page.element.querySelector(".ai-key-input");
    assert.equal(field.type, "password");
    field.value = "sk-or-v1-CANARY-glass-0123456789";
    page.element.querySelector(".ai-key-form").dispatchEvent({ type: "submit" });
    await flush(12);
    assert.deepEqual(posts[0], ["key", { key: "sk-or-v1-CANARY-glass-0123456789" }]);
    assert.doesNotMatch(page.element.textContent, /CANARY/);
    assert.equal(page.element.querySelector(".ai-key-input").value ?? "", "");
    assert.match(page.element.textContent, /Credit left: \$8\.75/);
    assert.match(page.element.textContent, /✓ Key saved/);
    // Pick a model.
    const rows = page.element.querySelectorAll(".ai-model-row");
    assert.equal(rows.length, 2);
    assert.match(rows[0].textContent, /\$3 in · \$15 out per M tokens/);
    assert.match(rows[1].textContent, /Free/);
    const radio = rows[1].querySelector("input");
    radio.checked = true;
    radio.dispatchEvent({ type: "change" });
    await flush(12);
    assert.deepEqual(posts.at(-1), ["settings", { model: "acme/tiny:free" }]);
    assert.match(page.element.textContent, /Default: Acme: Tiny \(free\)/);
    // Models without tools can be shown but not picked.
    const all = [...page.element.querySelectorAll(".ai-toggle input")][1];
    all.checked = true;
    all.dispatchEvent({ type: "change" });
    await flush(12);
    const chat = page.element.querySelectorAll(".ai-model-row").find((r) => /Chat only/.test(r.textContent));
    assert.equal(chat.querySelector("input").disabled, true);
    assert.match(chat.textContent, /no tools/);
    // Autonomy, privacy and limits.
    const workspace = page.element.querySelectorAll(".ai-choice input")[1];
    workspace.checked = true;
    workspace.dispatchEvent({ type: "change" });
    await flush(12);
    assert.deepEqual(posts.at(-1), ["settings", { autonomy: "workspace" }]);
    const privacy = page.element.querySelector(".ai-privacy input");
    privacy.checked = false;
    privacy.dispatchEvent({ type: "change" });
    await flush(12);
    assert.deepEqual(posts.at(-1), ["settings", { privateOnly: false }]);
    const caps = page.element.querySelectorAll(".ai-cap input");
    caps[0].value = "5";
    page.element.querySelector(".ai-caps").dispatchEvent({ type: "submit" });
    await flush(12);
    assert.deepEqual(posts.at(-1), ["settings", { dailyCapUsd: 5, monthlyCapUsd: 30 }]);
    assert.match(page.element.textContent, /It can never: approve anything, delete anything/);
    assert.match(page.element.textContent, /Proposes phone work/);
  } finally {
    page.destroy();
  }
});

// ------------------------------------------------------------------------------------------------ the panel

test("Ask AI without a key or model shows the setup card", async () => {
  installMiniDom();
  const gateway = fakeGateway({ "GET /v1/cc/ai": () => status({ keySaved: false, model: null }) });
  const { ctx, went } = ctxWith(gateway.fetch);
  const panel = createAiPanel(() => ctx);
  document.body.append(panel.element);
  try {
    panel.open(null);
    await flush(12);
    assert.match(panel.element.textContent, /Connect your AI/);
    assert.match(panel.element.textContent, /Add your OpenRouter key/);
    panel.element.querySelector(".ai-setup .btn").click();
    assert.deepEqual(went.at(-1), { name: "command", tab: "ai" });
    assert.equal(panel.isOpen(), false);
  } finally {
    panel.destroy();
  }
});

test("Ask AI about a page: send, watch it work, open a mention, apply one proposal and discard another", async () => {
  installMiniDom();
  const PAGE = "pg_weekly0001";
  const calls = [];
  let polls = 0;
  let convo = { id: "ai_conv00001", title: "New conversation", pageId: PAGE, model: null, state: "idle", detail: "", createdAt: 1, updatedAt: 1, openProposals: 0,
    costUsd: 0, messages: [], proposals: [] };
  const answered = () => ({ ...convo, state: "idle", title: "Plan Friday", costUsd: 0.0031, openProposals: 2,
    messages: [
      { seq: 1, role: "user", text: "Plan Friday", at: 1, activity: [] },
      { seq: 2, role: "assistant", text: "Proposed a Friday section; it mentions @[routine:rtn_answer001|Answer orders].", at: 2, model: "acme/planner-large", costUsd: 0.0031,
        activity: [{ label: "Read “Weekly plan”", outcome: "done", proposalId: null, error: null },
          { label: "Added to “Weekly plan”", outcome: "proposed", proposalId: "prp_add000001", error: null },
          { label: "Task: Post the mug video", outcome: "proposed", proposalId: "prp_task00001", error: null }] },
    ],
    proposals: [
      { id: "prp_add000001", conversationId: convo.id, tool: "append_to_page", kind: "workspace", summary: "Add 2 blocks to “Weekly plan”", preview: "## Friday\n- [ ] Ship mugs", state: "open", result: null, createdAt: 3, decidedAt: null },
      { id: "prp_task00001", conversationId: convo.id, tool: "create_task", kind: "phone", summary: "Start a task on any ready phone: “Post the mug video”", preview: "Post the mug video", state: "open", result: null, createdAt: 3, decidedAt: null },
    ] });
  const gateway = fakeGateway({
    "GET /v1/cc/ai": () => status(),
    "GET /v1/cc/ai/models": () => ({ models: MODELS, total: 2 }),
    [`GET /v1/cc/pages/${PAGE}`]: () => ({ id: PAGE, parentId: null, title: "Weekly plan", icon: "🗓️", position: 1, createdAt: 1, updatedAt: 1, blocks: [], version: 3, archivedAt: null, path: [], children: [], backlinks: [] }),
    "POST /v1/cc/ai/conversations": ({ body }) => { calls.push(["create", body]); return convo; },
    [`POST /v1/cc/ai/conversations/${convo.id}/messages`]: ({ body }) => {
      calls.push(["send", body]);
      convo = { ...convo, state: "working", detail: "Reading “Weekly plan”…", messages: [{ seq: 1, role: "user", text: body.text, at: 1, activity: [] }] };
      return convo;
    },
    [`GET /v1/cc/ai/conversations/${convo.id}`]: () => {
      polls += 1;
      if (polls === 2) convo = answered();
      return convo;
    },
    "POST /v1/cc/ai/proposals/prp_add000001/apply": () => {
      calls.push(["apply"]);
      convo = { ...convo, proposals: convo.proposals.map((p) => (p.id === "prp_add000001" ? { ...p, state: "applied", result: { pageId: PAGE, added: 2 } } : p)) };
      return convo.proposals[0];
    },
    "POST /v1/cc/ai/proposals/prp_task00001/discard": () => {
      calls.push(["discard"]);
      convo = { ...convo, proposals: convo.proposals.map((p) => (p.id === "prp_task00001" ? { ...p, state: "discarded" } : p)) };
      return convo.proposals[1];
    },
  });
  const { ctx, went } = ctxWith(gateway.fetch);
  const changed = [];
  const unlisten = workspaceBus.onPageChanged((id) => changed.push(id));
  const panel = createAiPanel(() => ctx);
  document.body.append(panel.element);
  try {
    panel.open(PAGE);
    await flush(12);
    assert.match(panel.element.textContent, /About: Weekly plan/);
    assert.match(panel.element.textContent, /What should we do with this page\?/);
    assert.match(panel.element.textContent, /Default · Acme: Planner Large/);
    assert.match(panel.element.textContent, /Today \$0\.12 of \$2\.00/);
    const input = panel.element.querySelector(".ai-input");
    input.value = "Plan Friday";
    input.dispatchEvent({ type: "keydown", key: "Enter", shiftKey: false, preventDefault() {} });
    await flush(12);
    assert.deepEqual(calls.slice(0, 2), [["create", { pageId: PAGE }], ["send", { text: "Plan Friday" }]]);
    assert.match(panel.element.textContent, /Reading “Weekly plan”…/);
    assert.match(panel.element.querySelector(".ai-foot").textContent, /Stop/);
    await sleep(2100);
    await flush(12);
    assert.match(panel.element.textContent, /Proposed a Friday section/);
    assert.match(panel.element.textContent, /▸ 3 steps/);
    assert.match(panel.element.textContent, /This chat \$0\.0031/);
    panel.element.querySelector(".ai-steps").click();
    assert.match(panel.element.textContent, /Read “Weekly plan”/);
    // A mention opens where it lives.
    panel.element.querySelector(".ai-mention").click();
    assert.deepEqual(went.at(-1), { name: "command", tab: "routines" });
    // Two proposals: the page edit is applied; the phone task is discarded.
    const cards = panel.element.querySelectorAll(".ai-prop");
    assert.equal(cards.length, 2);
    assert.match(cards[0].textContent, /Add 2 blocks to “Weekly plan”/);
    assert.match(cards[0].textContent, /Friday/);
    assert.match(cards[1].textContent, /The phone still asks you before it sends, pays, deletes or signs in/);
    assert.match(cards[1].textContent, /Start it/);
    cards[0].querySelector(".btn-primary").click();
    await flush(12);
    assert.deepEqual(calls.at(-1), ["apply"]);
    assert.deepEqual(changed, [PAGE, PAGE], "the open page checks for edits when the turn ends and again when one is applied");
    assert.match(panel.element.querySelectorAll(".ai-prop")[0].textContent, /✓ Applied/);
    panel.element.querySelectorAll(".ai-prop")[1].querySelector(".btn-ghost").click();
    await flush(12);
    assert.deepEqual(calls.at(-1), ["discard"]);
    assert.match(panel.element.querySelectorAll(".ai-prop")[1].textContent, /Discarded/);
    panel.element.dispatchEvent({ type: "keydown", key: "Escape" });
    assert.equal(panel.isOpen(), false);
  } finally {
    unlisten();
    panel.destroy();
  }
});
