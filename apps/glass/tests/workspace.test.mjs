import test from "node:test";
import assert from "node:assert/strict";
import { installMiniDom } from "./helpers/mini-dom.mjs";
import { fakeGateway, flush } from "./helpers/fakeGateway.mjs";
import { GatewayClient } from "../.test-dist/services/gateway.js";
import { GlassApp } from "../.test-dist/app.js";
import { modeOf, parseRoute, routeHref } from "../.test-dist/core/router.js";
import {
  buildTree, deleteRange, filterSlash, insertRef, itemsByStatus, listNumber, markdownShortcut, monthGrid, moveBlock, normalizeSpans,
  parseBlock, parsePage, searchDirectory, splitSpans, textBefore, textLength,
  mergeBlocks,
} from "../.test-dist/services/pages.js";
import { workspaceBus } from "../.test-dist/workspace/directory.js";
import { createEditor } from "../.test-dist/workspace/editor.js";
import { createPlanView } from "../.test-dist/workspace/plan.js";
import { createPageView } from "../.test-dist/workspace/pageView.js";
import { createWorkspaceHome } from "../.test-dist/workspace/home.js";
import { createCommandPage } from "../.test-dist/pages/commandPage.js";

const PAGE = "pg_weekly0001";
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const meta = (id, title, parentId = null, extra = {}) => ({ id, parentId, title, icon: "", position: 1, updatedAt: 1, createdAt: 1, ...extra });
const ref = (kind, id, label) => ({ kind, id, label });

function ctxWith(fetch, extra = {}) {
  const went = [];
  return { went, ctx: { client: new GatewayClient({ token: "t", fetch }), version: "1.0.0-alpha.34", devices: [], device: null, devicesError: null,
    navigate: (r) => went.push(r), selectDevice() {}, refreshDevices: async () => {}, ...extra } };
}

const DIRECTORY_ROUTES = {
  "GET /v1/cc/pages": () => ({ pages: [meta(PAGE, "Weekly plan"), meta("pg_replies0001", "Shop replies", PAGE)], templates: [] }),
  "GET /v1/cc/routines": () => ({ routines: [{ id: "rtn_answer001", title: "Answer shop orders", scheduleLabel: "Every hour", deviceIds: [], schedule: { kind: "every", minutes: 60 } }] }),
  "GET /v1/cc/tasks": () => ({ tasks: [] }),
  "GET /v1/cc/accounts": () => ({ accounts: [{ id: "acc_shop00001", handle: "@corner.shop", service: "com.shop" }] }),
  "GET /v1/cc/connections": () => ({ connections: [] }),
  "GET /v1/cc/approvals": () => ({ approvals: [] }),
};

// ------------------------------------------------------------------------------------------------ pure pieces

test("spans split, join, delete and take mentions as one character", () => {
  const spans = [{ t: "Ask " }, { ref: ref("device", "d1", "Pixel") }, { t: " to post", b: true }];
  assert.equal(textLength(spans), 13);
  assert.deepEqual(splitSpans(spans, 5), [[{ t: "Ask " }, { ref: ref("device", "d1", "Pixel") }], [{ t: " to post", b: true }]]);
  assert.deepEqual(splitSpans(spans, 2), [[{ t: "As" }], [{ t: "k " }, { ref: ref("device", "d1", "Pixel") }, { t: " to post", b: true }]]);
  assert.deepEqual(deleteRange([{ t: "hello /to" }], 6, 9), [{ t: "hello " }]);
  assert.deepEqual(insertRef([{ t: "Ask @" }], 4, ref("routine", "rtn_x0000001", "Orders")).map((s) => s.t ?? s.ref.label), ["Ask ", "Orders", " @"]);
  assert.deepEqual(normalizeSpans([{ t: "a" }, { t: "" }, { t: "b" }, { t: "c", i: true }]), [{ t: "ab" }, { t: "c", i: true }]);
  assert.equal(textBefore([{ t: "hi " }, { ref: ref("page", "pg_a0000001", "A") }, { t: " /" }], 6), "hi  /");
});

test("markdown shortcuts, the slash menu, the directory, lists, trees, boards and calendars", () => {
  assert.deepEqual(markdownShortcut("# Title"), { type: "h1", rest: "Title" });
  assert.deepEqual(markdownShortcut("[x] done"), { type: "todo", rest: "done", checked: true });
  assert.deepEqual(markdownShortcut("---"), { type: "divider", rest: "" });
  assert.equal(markdownShortcut("#hashtag"), null);
  assert.deepEqual(filterSlash("todo").map((i) => i.id), ["todo"]);
  assert.ok(filterSlash("kanban").some((i) => i.id === "board"));
  const entries = [{ ref: ref("page", "pg_a0000001", "Launch plan"), detail: "" }, { ref: ref("device", "d1", "Pixel 8"), detail: "Ready" },
    { ref: ref("routine", "rtn_a0000001", "Plan posts"), detail: "Daily" }];
  assert.deepEqual(searchDirectory(entries, "pla").map((e) => e.ref.label), ["Plan posts", "Launch plan"], "starts-with ranks first");
  assert.deepEqual(searchDirectory(entries, "phone").map((e) => e.ref.kind), ["device"], "kind names match too");
  const blocks = [{ type: "number" }, { type: "number" }, { type: "p" }, { type: "number" }];
  assert.deepEqual([0, 1, 3].map((i) => listNumber(blocks, i)), [1, 2, 1]);
  assert.deepEqual(moveBlock(["a", "b", "c"], 0, 2), ["b", "c", "a"]);
  const tree = buildTree([meta("pg_b0000001", "B", null, { position: 2 }), meta("pg_a0000001", "A"), meta("pg_c0000001", "C", "pg_a0000001"), meta("pg_o0000001", "Orphan", "pg_gone00001")]);
  assert.deepEqual(tree.map((n) => n.page.title), ["A", "Orphan", "B"]);
  assert.equal(tree[0].children[0].page.title, "C");
  assert.deepEqual(Object.values(itemsByStatus([{ status: "done" }, { status: "todo" }, { status: "todo" }])).map((l) => l.length), [2, 0, 1]);
  const weeks = monthGrid(2026, 8); // September 2026 starts on a Tuesday
  assert.equal(weeks[0][0], null);
  assert.equal(weeks[0][1].getDate(), 1);
  assert.ok(weeks.every((w) => w.length === 7));
  assert.equal(parseBlock({ id: "b1", type: "script" }), null);
  assert.equal(parsePage({ id: PAGE, blocks: [{ id: "bx", type: "p", text: [{ t: "x", html: "<b>" }, { ref: { kind: "vault", id: "v" } }] }] }).blocks[0].text.length, 1);
});

test("routes: the Command Center opens on its home, pages have their own address, and each route has a face", () => {
  assert.deepEqual(parseRoute("#/command"), { name: "command", tab: "home" });
  assert.deepEqual(parseRoute(`#/command/page/${PAGE}`), { name: "command", tab: "page", pageId: PAGE });
  assert.deepEqual(parseRoute("#/command/page/../../x"), { name: "command", tab: "home" });
  assert.deepEqual(parseRoute("#/command/trash"), { name: "command", tab: "trash" });
  assert.equal(routeHref({ name: "command", tab: "page", pageId: PAGE }), `#/command/page/${PAGE}`);
  assert.equal(modeOf({ name: "command", tab: "tasks" }), "command");
  assert.equal(modeOf({ name: "devices" }), "glass");
});

// ------------------------------------------------------------------------------------------------ the shell

function startApp(hash) {
  installMiniDom();
  const root = document.createElement("div");
  const location = { hash };
  const listeners = [];
  const gateway = fakeGateway({ ...DIRECTORY_ROUTES, "GET /v1/fleet": () => ({ devices: [] }), "GET /v1/cc/overview": () => ({}), "GET /v1/pc/welcome": () => ({ seen: true }) });
  const app = new GlassApp({ root, client: new GatewayClient({ token: "t", fetch: gateway.fetch }), version: "1.0.0-alpha.34", location, storage: null,
    setHash: (h) => { location.hash = h; listeners.forEach((fn) => fn()); }, onHashChange: (fn) => { listeners.push(fn); return () => {}; },
    setInterval: () => 1, clearInterval() {} });
  return { app, root, location, gateway };
}

test("the logo switches between the Command Center and Glass, each with its own sidebar, and remembers where you were", async () => {
  const { app, root, location } = startApp(`#/command/tasks`);
  await app.start();
  await flush();
  try {
    const brand = root.querySelector(".brand-switch");
    assert.equal(brand.dataset.mode, "command");
    assert.match(brand.textContent, /Command Center/);
    assert.match(brand.getAttribute("aria-label"), /Switch to Cyclone Glass/);
    assert.ok(root.querySelector(".ws-sidebar"), "the workspace sidebar");
    assert.equal(root.querySelector(".device-picker"), null, "the Glass sidebar is not shown");
    assert.ok(brand.querySelector(".brand-front .logo-command") && brand.querySelector(".brand-back .logo-cyclone"), "Command Center in front, Cyclone behind");
    assert.match(root.querySelector(".page-title").textContent, /Tasks/);
    assert.match(root.querySelector(".ws-tree").textContent, /Weekly plan/);
    brand.click();
    await flush();
    assert.equal(location.hash, "#/home");
    const glassBrand = root.querySelector(".brand-switch");
    assert.equal(glassBrand.dataset.mode, "glass");
    assert.match(glassBrand.textContent, /Cyclone Glass/);
    assert.ok(glassBrand.querySelector(".brand-front .logo-cyclone"), "Cyclone in front in Glass");
    assert.ok(root.querySelector(".device-picker"));
    assert.equal(root.querySelector(".ws-sidebar"), null);
    assert.equal(root.querySelectorAll(".nav-item").some((n) => /Command Center/.test(n.textContent)), false, "the logo is the way in");
    location.hash = "#/settings";
    app["onHashChange"]();
    glassBrand.click();
    await flush();
    assert.equal(location.hash, "#/command/tasks", "back where you were in the Command Center");
    root.querySelector(".brand-switch").click();
    assert.equal(location.hash, "#/settings", "and back where you were in Glass");
  } finally {
    app.stop();
  }
});

test("the page tree nests pages, opens a new page, renames and moves to the trash", async () => {
  const { app, root, location, gateway } = startApp(`#/command/home`);
  const created = [];
  let tree = [meta(PAGE, "Weekly plan"), meta("pg_replies0001", "Shop replies", PAGE)];
  gateway.calls.length = 0;
  const routes = {
    "GET /v1/cc/pages": () => ({ pages: tree }),
    "POST /v1/cc/pages": ({ body }) => { created.push(body); const p = { ...meta("pg_new0000001", "Untitled", body.parentId ?? null), blocks: [], version: 1, path: [], children: [], backlinks: [] }; tree = [...tree, p]; return p; },
    [`GET /v1/cc/pages/${PAGE}`]: () => ({ ...meta(PAGE, "Weekly plan"), blocks: [], version: 3, path: [], children: [], backlinks: [] }),
    [`POST /v1/cc/pages/${PAGE}`]: ({ body }) => { tree = tree.map((p) => (p.id === PAGE ? { ...p, title: body.title } : p)); return { ...meta(PAGE, body.title), blocks: [], version: 4, path: [], children: [], backlinks: [] }; },
    [`POST /v1/cc/pages/${PAGE}/archive`]: () => { tree = tree.filter((p) => p.id !== PAGE && p.parentId !== PAGE); return { archived: 2 }; },
  };
  const original = gateway.fetch;
  app.options.client = new GatewayClient({ token: "t", fetch: fakeGateway({ ...DIRECTORY_ROUTES, "GET /v1/fleet": () => ({ devices: [] }), ...routes }).fetch });
  void original;
  await app.start();
  await flush();
  try {
    const rows = () => root.querySelectorAll(".ws-page");
    assert.deepEqual(rows().map((r) => r.textContent.includes("Weekly plan")), [true], "the child waits inside its closed parent");
    rows()[0].querySelector(".ws-caret").click();
    assert.equal(rows().length, 2);
    assert.equal(rows()[1].getAttribute("style") ?? rows()[1].style["--depth"], "1");
    rows()[0].querySelector(".ws-page-add").click();
    await flush();
    assert.deepEqual(created[0], { parentId: PAGE });
    assert.equal(location.hash, "#/command/page/pg_new0000001");
    rows()[0].querySelector(".ws-page-more").click();
    const menu = root.querySelector(".ws-menu");
    menu.querySelectorAll("button").find((b) => b.textContent === "Rename").click();
    const input = root.querySelector(".ws-rename");
    input.value = "Week 40";
    input.dispatchEvent({ type: "keydown", key: "Enter" });
    await flush(12);
    assert.match(root.querySelector(".ws-tree").textContent, /Week 40/);
    root.querySelectorAll(".ws-page")[0].querySelector(".ws-page-more").click();
    root.querySelector(".ws-menu").querySelectorAll("button").find((b) => b.textContent === "Move to trash").click();
    await flush(12);
    assert.doesNotMatch(root.querySelector(".ws-tree").textContent, /Week 40/);
  } finally {
    app.stop();
  }
});

// ------------------------------------------------------------------------------------------------ the editor

function editorWith(blocks, extraRoutes = {}) {
  installMiniDom();
  workspaceBus.pagesChanged();
  const gateway = fakeGateway({ ...DIRECTORY_ROUTES, ...extraRoutes });
  const { ctx, went } = ctxWith(gateway.fetch);
  const changes = [];
  const editor = createEditor(ctx, PAGE, blocks, (b) => changes.push(b));
  return { editor, changes, went, gateway, texts: () => editor.element.querySelectorAll(".ws-text") };
}
const type = (editable, text) => {
  editable.textContent = text;
  editable.dispatchEvent({ type: "input" });
};
const key = (editable, k, extra = {}) => editable.dispatchEvent({ type: "keydown", key: k, ...extra });

test("typing: markdown shortcuts turn blocks into headings, lists and dividers; Enter splits and continues a list", async () => {
  const { editor, changes, texts } = editorWith([]);
  try {
    type(texts()[0], "# Launch week");
    let last = changes.at(-1);
    assert.equal(last[0].type, "h1");
    assert.deepEqual(last[0].text, [{ t: "Launch week" }]);
    key(texts()[0], "Enter");
    type(texts()[1], "[] Film the mug");
    assert.equal(changes.at(-1)[1].type, "todo");
    key(texts()[1], "Enter");
    assert.equal(changes.at(-1)[2].type, "todo", "Enter on a to-do makes the next one a to-do");
    key(texts()[2], "Enter");
    assert.equal(changes.at(-1)[2].type, "p", "Enter on an empty to-do ends the list");
    type(texts()[2], "---");
    last = changes.at(-1);
    assert.deepEqual(last.map((b) => b.type), ["h1", "todo", "divider", "p"]);
    const box = editor.element.querySelectorAll("input").find((i) => i.type === "checkbox");
    box.checked = true;
    box.dispatchEvent({ type: "change" });
    assert.equal(changes.at(-1)[1].checked, true);
  } finally {
    editor.destroy();
  }
});

test("the slash menu inserts blocks, including a live view and a plan", async () => {
  const { editor, changes, texts } = editorWith([{ id: "bfirst01", type: "p", text: [] }]);
  try {
    type(texts()[0], "/");
    assert.ok(editor.element.querySelector(".ws-popover"), "the menu opens");
    type(texts()[0], "/to-do");
    const rows = editor.element.querySelectorAll(".ws-pop-row");
    assert.equal(rows.length, 1);
    key(texts()[0], "Enter");
    assert.equal(changes.at(-1)[0].type, "todo");
    assert.equal(editor.element.querySelector(".ws-popover"), null);
    type(texts()[0], "Plan the posts /");
    type(texts()[0], "Plan the posts /plan b");
    key(texts()[0], "Enter");
    const blocks = changes.at(-1);
    assert.deepEqual(blocks.map((b) => b.type), ["todo", "board", "p"], "a plan goes below the text, with a line to keep typing");
    assert.deepEqual(blocks[0].text, [{ t: "Plan the posts " }]);
    type(texts()[1], "/");
    key(texts()[1], "Escape");
    assert.equal(editor.element.querySelector(".ws-popover"), null);
  } finally {
    editor.destroy();
  }
});

test("@ mentions a routine, a page or an account as a reference that opens where it lives", async () => {
  const { editor, changes, texts, went } = editorWith([{ id: "bfirst01", type: "p", text: [] }]);
  try {
    await flush();
    type(texts()[0], "Ask @");
    await flush();
    type(texts()[0], "Ask @answ");
    const rows = editor.element.querySelectorAll(".ws-pop-row");
    assert.equal(rows.length, 1);
    assert.match(rows[0].textContent, /Answer shop orders/);
    rows[0].click();
    const text = changes.at(-1)[0].text;
    assert.deepEqual(text, [{ t: "Ask " }, { ref: { kind: "routine", id: "rtn_answer001", label: "Answer shop orders" } }, { t: " " }]);
    const chip = editor.element.querySelector(".ws-mention");
    assert.equal(chip.getAttribute("contenteditable"), "false");
    chip.click();
    assert.deepEqual(went.at(-1), { name: "command", tab: "routines" });
  } finally {
    editor.destroy();
  }
});

test("Backspace in an empty line removes it; the block menu changes, duplicates, moves and deletes", async () => {
  const { editor, changes, texts } = editorWith([{ id: "bfirst01", type: "p", text: [{ t: "One" }] }, { id: "bsecond1", type: "p", text: [] }]);
  try {
    key(texts()[1], "Backspace");
    assert.deepEqual(changes.at(-1).map((b) => b.id), ["bfirst01"]);
    editor.element.querySelector(".ws-handle").click();
    const menu = () => editor.element.querySelector(".ws-popover");
    menu().querySelectorAll(".ws-pop-row").find((r) => /Heading 2/.test(r.textContent)).click();
    assert.equal(changes.at(-1)[0].type, "h2");
    editor.element.querySelector(".ws-handle").click();
    menu().querySelectorAll(".ws-pop-row").find((r) => /Duplicate/.test(r.textContent)).click();
    assert.equal(changes.at(-1).length, 2);
    assert.notEqual(changes.at(-1)[0].id, changes.at(-1)[1].id);
    editor.element.querySelectorAll(".ws-handle")[1].click();
    menu().querySelectorAll(".ws-pop-row").find((r) => /Delete/.test(r.textContent)).click();
    assert.equal(changes.at(-1).length, 1);
  } finally {
    editor.destroy();
  }
});

// ------------------------------------------------------------------------------------------------ plans, pages, home

test("a plan board adds cards, changes their status and sends one to the phone it links to", async () => {
  installMiniDom();
  const tasks = [];
  const gateway = fakeGateway({ ...DIRECTORY_ROUTES, "POST /v1/cc/tasks": ({ body }) => { tasks.push(body); return { id: "tsk_card00001", title: body.title, goal: body.goal, status: "scheduled" }; } });
  const { ctx } = ctxWith(gateway.fetch);
  const changes = [];
  const plan = createPlanView(ctx, { id: "bplan001", type: "board", title: "Launch", layout: "board", items: [
    { id: "ifilm001", title: "Film the mug", status: "todo", due: null, refs: [{ kind: "device", id: "phone-a", label: "Pixel 8" }], note: "Short, 9:16.", taskId: null }] },
  (b) => changes.push(b));
  try {
    const cols = plan.element.querySelectorAll(".ws-col");
    assert.deepEqual(cols.map((c) => c.dataset.status), ["todo", "doing", "done"]);
    cols[1].querySelectorAll("button").find((b) => b.getAttribute("aria-label") === "New card in Doing").click();
    const title = plan.element.querySelector(".ws-card-name");
    title.value = "Reply to Sam";
    title.dispatchEvent({ type: "input" });
    assert.equal(changes.at(-1).items[1].title, "Reply to Sam");
    assert.equal(changes.at(-1).items[1].status, "doing");
    plan.element.querySelector(".ws-peek").dispatchEvent({ type: "click", target: plan.element.querySelector(".ws-peek") });
    assert.equal(plan.element.querySelector(".ws-peek"), null, "clicking outside closes the card");
    plan.element.querySelectorAll(".ws-card")[0].click();
    const status = plan.element.querySelectorAll("select").find((s) => s.getAttribute("aria-label") === "Card status");
    status.value = "done";
    status.dispatchEvent({ type: "change" });
    assert.equal(changes.at(-1).items[0].status, "done");
    plan.element.querySelectorAll("button").find((b) => b.textContent === "Send to a phone").click();
    await flush();
    assert.deepEqual(tasks[0], { title: "Film the mug", goal: "Film the mug\n\nShort, 9:16.", deviceId: "phone-a" });
    assert.equal(changes.at(-1).items[0].taskId, "tsk_card00001");
    plan.element.querySelectorAll(".ws-seg-item").find((b) => b.textContent === "Calendar").click();
    assert.equal(changes.at(-1).layout, "calendar");
    assert.ok(plan.element.querySelector(".ws-cal-grid"));
    assert.match(plan.element.querySelector(".ws-undated").textContent, /No date · 2/);
  } finally {
    plan.destroy();
  }
});

test("a page saves by itself with its version, and merges when the page changed elsewhere", async () => {
  installMiniDom();
  workspaceBus.pagesChanged();
  const saves = [];
  let version = 5;
  let conflicts = 0;
  let extra = [];
  const page = () => ({ ...meta(PAGE, "Weekly plan", null, { icon: "🗓️" }), blocks: [{ id: "bone0001", type: "p", text: [{ t: "Hello" }] }, ...extra], version, archivedAt: null,
    path: [{ id: "pg_parent0001", title: "Shop", icon: "🛍️" }], children: [meta("pg_child00001", "Replies")], backlinks: [meta("pg_other00001", "Daily check")] });
  const gateway = fakeGateway({ ...DIRECTORY_ROUTES, [`GET /v1/cc/pages/${PAGE}`]: page,
    [`GET /v1/cc/pages/${PAGE}/version`]: () => ({ id: PAGE, version, archivedAt: null }),
    [`POST /v1/cc/pages/${PAGE}`]: ({ body }) => {
      saves.push(body);
      if (conflicts > 0) {
        conflicts -= 1;
        return new Response(JSON.stringify({ detail: { code: "PAGE_CHANGED", message: "changed" } }), { status: 409 });
      }
      version += 1;
      return { ...page(), blocks: body.blocks ?? page().blocks };
    } });
  const { ctx } = ctxWith(gateway.fetch);
  const view = createPageView(ctx, PAGE);
  try {
    await flush();
    assert.match(view.element.textContent, /Shop/);
    assert.match(view.element.textContent, /Pages inside/);
    assert.match(view.element.textContent, /Mentioned in 1 page/);
    assert.match(view.element.textContent, /Ask AI/);
    const title = view.element.querySelector(".ws-title");
    title.value = "Week 40";
    title.dispatchEvent({ type: "input" });
    await sleep(700);
    await flush();
    assert.deepEqual(saves[0], { version: 5, title: "Week 40" });
    const text = view.element.querySelector(".ws-text");
    type(text, "Hello there");
    await sleep(700);
    await flush();
    assert.equal(saves[1].version, 6);
    assert.deepEqual(saves[1].blocks[0].text, [{ t: "Hello there" }]);
    // Elsewhere (the AI, another window) the page moved on: its first line is back to "Hello" there and a line was added.
    version = 9;
    extra = [{ id: "bai00001", type: "p", text: [{ t: "Added by the AI" }] }];
    conflicts = 1;
    type(view.element.querySelector(".ws-text"), "My edit");
    await sleep(700);
    await flush(12);
    assert.match(view.element.querySelector(".ws-save").textContent, /Merged with changes made elsewhere/);
    await sleep(700);
    await flush(12);
    const last = saves.at(-1);
    assert.equal(last.version, 9, "the merge saves on top of the newest version");
    assert.deepEqual(last.blocks.map((b) => b.text[0].t), ["My edit", "Added by the AI"], "both sides' edits survive");
    assert.deepEqual([...view.element.querySelectorAll(".ws-text")].map((n) => n.textContent), ["My edit", "Added by the AI"]);
  } finally {
    view.destroy();
  }
});

test("merging blocks: mine win where I changed them, theirs elsewhere, new blocks keep their place", () => {
  const p = (id, t) => ({ id, type: "p", text: [{ t }] });
  const base = [p("b0001", "one"), p("b0002", "two"), p("b0003", "three")];
  const mine = [p("b0001", "ONE"), p("bnew1", "mine new"), p("b0002", "two")];
  const theirs = [p("b0001", "one"), p("b0002", "TWO"), p("b0003", "three"), p("bnew2", "theirs new")];
  assert.deepEqual(mergeBlocks(base, mine, theirs).map((b) => b.text[0].t), ["ONE", "mine new", "TWO", "theirs new"]);
  // They changed a block I deleted: it stays. I changed a block they deleted: it stays.
  const theirs2 = [p("b0002", "two"), p("b0003", "THREE")];
  const mine2 = [p("b0001", "ONE!"), p("b0002", "two")];
  assert.deepEqual(mergeBlocks(base, mine2, theirs2).map((b) => b.text[0].t), ["ONE!", "two", "THREE"]);
});

test("home: templates start a page, and what is waiting, running and next is listed", async () => {
  installMiniDom();
  const made = [];
  const gateway = fakeGateway({ ...DIRECTORY_ROUTES,
    "POST /v1/cc/pages": ({ body }) => { made.push(body); return { ...meta("pg_made000001", "Weekly plan"), blocks: [], version: 1, path: [], children: [], backlinks: [] }; },
    "GET /v1/cc/approvals": () => ({ approvals: [{ id: "apv_1", kind: "spend", text: "Use Corner Shop: replyToOrder?", title: "Answer", createdAt: 1 }] }),
    "GET /v1/cc/routines": () => ({ routines: [{ id: "rtn_a0000001", title: "Answer shop orders", scheduleLabel: "Every hour", nextRunAt: Date.now() + 40 * 60_000, deviceIds: [] }] }) });
  const { ctx, went } = ctxWith(gateway.fetch);
  const home = createWorkspaceHome(ctx, () => new Date(2026, 8, 28, 9, 0));
  try {
    await flush();
    assert.match(home.element.textContent, /Good morning/);
    assert.match(home.element.textContent, /Waiting for you · 1/);
    assert.match(home.element.textContent, /in 40 min · Every hour/);
    home.element.querySelectorAll(".ws-template").find((b) => /Weekly plan/.test(b.textContent)).click();
    await flush();
    assert.deepEqual(made[0], { template: "weekly" });
    assert.deepEqual(went.at(-1), { name: "command", tab: "page", pageId: "pg_made000001" });
  } finally {
    home.destroy();
  }
});

test("the databases in the workspace: a clean header, the form behind New, and tasks as a board", async () => {
  installMiniDom();
  const gateway = fakeGateway({ ...DIRECTORY_ROUTES, "GET /v1/cc/overview": () => ({}),
    "GET /v1/cc/tasks": () => ({ tasks: [{ id: "tsk_a0000001", title: "Post the video", status: "running", createdAt: 1, cause: "" }, { id: "tsk_b0000001", title: "Reply", status: "succeeded", createdAt: 1, cause: "" }] }) });
  const { ctx } = ctxWith(gateway.fetch);
  const page = createCommandPage(ctx, "tasks", { workspace: true });
  try {
    await flush();
    assert.equal(page.element.querySelector(".page-title").textContent, "Tasks");
    assert.equal(page.element.querySelector(".cc-stats"), null);
    const form = page.element.querySelector(".cc-form-area");
    assert.equal(form.hidden, true);
    page.element.querySelectorAll("button").find((b) => b.textContent === "New task").click();
    assert.equal(form.hidden, false);
    page.element.querySelectorAll("button").find((b) => b.textContent === "Board").click();
    const cols = page.element.querySelectorAll(".ws-col");
    assert.deepEqual(cols.map((c) => c.querySelector(".ws-col-head").textContent), ["Waiting · 0", "Running · 1", "Needs you · 0", "Done · 1", "Stopped · 0"]);
  } finally {
    page.destroy();
  }
});

test("a refused save (a secret in the text) is not retried; the next edit saves", async () => {
  installMiniDom();
  workspaceBus.pagesChanged();
  const saves = [];
  const page = { ...meta(PAGE, "Notes"), blocks: [{ id: "bone0001", type: "p", text: [] }], version: 1, archivedAt: null, path: [], children: [], backlinks: [] };
  const gateway = fakeGateway({ ...DIRECTORY_ROUTES, [`GET /v1/cc/pages/${PAGE}`]: () => page,
    [`POST /v1/cc/pages/${PAGE}`]: ({ body }) => {
      saves.push(body);
      if (JSON.stringify(body).includes("password:")) return new Response(JSON.stringify({ detail: { code: "INVALID_REQUEST", message: "That looks like a password, code or key." } }), { status: 400 });
      return { ...page, version: 2 };
    } });
  const { ctx } = ctxWith(gateway.fetch);
  const view = createPageView(ctx, PAGE);
  try {
    await flush();
    type(view.element.querySelector(".ws-text"), "password: hunter2");
    await sleep(700);
    await flush();
    assert.match(view.element.querySelector(".ws-save").textContent, /Not saved: That looks like a password/);
    assert.ok(view.element.querySelector(".ws-block").classList.contains("ws-warn"), "the block is marked while you type");
    await sleep(700);
    assert.equal(saves.length, 1, "not retried");
    type(view.element.querySelector(".ws-text"), "Ask the vault instead");
    await sleep(700);
    await flush();
    assert.equal(saves.length, 2);
    assert.deepEqual(saves[1].blocks[0].text, [{ t: "Ask the vault instead" }]);
    assert.equal(view.element.querySelector(".ws-save").textContent, "Saved");
  } finally {
    view.destroy();
  }
});
