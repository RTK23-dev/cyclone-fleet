import test from "node:test";
import assert from "node:assert/strict";
import { installMiniDom } from "./helpers/mini-dom.mjs";
import { fakeGateway, flush } from "./helpers/fakeGateway.mjs";
import { GatewayClient } from "../.test-dist/services/gateway.js";
import { cellText, parseTable } from "../.test-dist/services/tables.js";
import { createTableBlock, renderCell } from "../.test-dist/workspace/tableView.js";
import { createButtonEditor, describeActions } from "../.test-dist/workspace/buttonEditor.js";

installMiniDom();
const mounted = [];
test.afterEach(() => {
  while (mounted.length) mounted.pop().destroy();
});

const T = "tb_customers001";
const view = { id: "vw_table001", name: "All", layout: "table", position: 1,
  config: { filters: [], match: "and", sorts: [], groupBy: null, hidden: [], widths: {}, dateProp: null } };
const STATUS = { id: "pr_status01", name: "Status", type: "status", position: 2, config: { options: [
  { id: "op_todo0001", name: "To do", color: "gray", group: "todo" }, { id: "op_done0001", name: "Checked", color: "green", group: "done" }] } };
const BUTTON = { id: "pr_check001", name: "Check WhatsApp", type: "button", position: 5, config: {
  label: "Check WhatsApp", color: "blue",
  actions: [{ do: "prompt", prompt: "Check WhatsApp for {{Name}}" }, { do: "open", url: "https://wa.me" }],
  runOn: { kind: "row", propId: "pr_phone001" }, then: { success: { pr_status01: "op_done0001" }, summary: "pr_notes001" } } };
const TABLE = {
  id: T, title: "Customers", icon: "👥", description: "", version: 1, updatedAt: 1, rows: 1, archivedAt: null, views: [view],
  properties: [
    { id: "pr_name0001", name: "Name", type: "title", config: {}, position: 1 }, STATUS,
    { id: "pr_notes001", name: "Notes", type: "text", config: {}, position: 3 },
    { id: "pr_phone001", name: "Phone", type: "relation", config: { target: "sys:phones" }, position: 4 }, BUTTON,
  ],
};
const ROW = { id: "rw_anna000001", version: 1, createdAt: 1, updatedAt: 1, hasPage: false,
  cells: { pr_name0001: "Anna", pr_phone001: ["pixel8-abc"], pr_check001: null } };

test("button properties parse, read as text and show their latest run", () => {
  const table = parseTable(TABLE);
  const button = table.properties.find((p) => p.type === "button");
  assert.equal(button.config.label, "Check WhatsApp");
  assert.deepEqual(button.config.runOn, { kind: "row", propId: "pr_phone001" });
  assert.equal(button.config.actions.length, 2);
  assert.equal(describeActions(button, table), "Asks: Check WhatsApp for {{Name}} · Opens wa.me");
  const run = { state: "needs_you", label: "Needs you", at: 1, taskIds: ["tsk_1"], summary: "" };
  assert.equal(cellText(button, run), "Needs you");
  assert.match(renderCell(button, run).textContent, /Needs you/);
});

test("pressing a row's button starts it, opens its link and shows the run", async () => {
  const pressed = [];
  const opened = [];
  globalThis.open = (url) => { opened.push(url); return null; };
  let row = ROW;
  const gw = fakeGateway({
    [`GET /v1/cc/tables/${T}/rows`]: () => ({ table: TABLE, view, rows: [row], total: 1, links: { "pixel8-abc": { label: "Pixel 8", tableId: "sys:phones" } } }),
    [`POST /v1/cc/tables/${T}/rows/${ROW.id}/buttons/${BUTTON.id}`]: () => {
      pressed.push(true);
      row = { ...ROW, cells: { ...ROW.cells, pr_check001: { state: "queued", label: "Queued", at: 2, taskIds: ["tsk_1"], summary: "" } } };
      return { runId: "btn_1", state: "queued", tasks: ["tsk_1"], open: ["https://wa.me", "javascript:alert(1)"] };
    },
  });
  const ctx = { client: new GatewayClient({ token: "t", fetch: gw.fetch }), navigate() {}, devices: [], device: null };
  const v = createTableBlock(ctx, { id: "btab00001", type: "table", tableId: T, viewId: null }, () => {});
  mounted.push(v);
  await flush();
  const press = v.element.querySelectorAll("button").find((b) => b.textContent === "Check WhatsApp");
  assert.ok(press, "the row shows its button");
  assert.match(press.className, /tb-button-blue/);
  press.click();
  await flush();
  assert.equal(pressed.length, 1);
  assert.deepEqual(opened, ["https://wa.me"], "only http(s) links open");
  assert.match(v.element.textContent, /Queued/);
  const again = v.element.querySelectorAll("button").find((b) => b.textContent === "Check WhatsApp");
  assert.equal(again.disabled, true, "a running button can't be pressed twice");
  delete globalThis.open;
});

test("the button editor reads what the owner set up", () => {
  const table = parseTable(TABLE);
  const ctx = { client: new GatewayClient({ token: "t", fetch: async () => new Response("{}") }), devices: [{ id: "pixel8-abc", name: "Pixel 8" }] };
  const editor = createButtonEditor(ctx, table, table.properties.find((p) => p.type === "button"));
  const config = editor.read();
  assert.equal(config.label, "Check WhatsApp");
  assert.equal(config.color, "blue");
  assert.deepEqual(config.actions, [{ do: "prompt", prompt: "Check WhatsApp for {{Name}}" }, { do: "open", url: "https://wa.me" }]);
  assert.deepEqual(config.runOn, { kind: "row", propId: "pr_phone001" });
  assert.deepEqual(config.then, { success: { pr_status01: "op_done0001" }, summary: "pr_notes001" });
  const fresh = createButtonEditor(ctx, table);
  const label = fresh.element.querySelectorAll("input").find((i) => i.getAttribute("aria-label") === "Button label");
  label.value = "";
  assert.throws(() => fresh.read(), /label/);
  label.value = "Ping";
  assert.throws(() => fresh.read(), /what the phone should do/);
  const prompt = fresh.element.querySelectorAll("textarea").find((t) => t.getAttribute("aria-label") === "Prompt");
  prompt.value = "Say hi to {{Name}}";
  const where = fresh.element.querySelectorAll("select").find((s) => s.getAttribute("aria-label") === "Runs on");
  where.value = "phone:pixel8-abc";
  assert.deepEqual(fresh.read().runOn, { kind: "phone", deviceId: "pixel8-abc" });
  assert.equal(fresh.read().label, "Ping");
});
