import test from "node:test";
import assert from "node:assert/strict";
import { installMiniDom } from "./helpers/mini-dom.mjs";
import { fakeGateway, flush } from "./helpers/fakeGateway.mjs";
import { GatewayClient } from "../.test-dist/services/gateway.js";
import {
  cellText, defaultFilter, formatDate, formatNumber, nextColor, parseInput, parseRows, parseTable, rowsOfGroup, valueForGroup, visibleProps,
} from "../.test-dist/services/tables.js";
import { filterSlash, parseBlock } from "../.test-dist/services/pages.js";
import { createTableBlock, renderCell } from "../.test-dist/workspace/tableView.js";

installMiniDom();
const mounted = [];
test.afterEach(() => {
  while (mounted.length) mounted.pop().destroy();
  for (const p of document.body.querySelectorAll(".tb-peek")) p.remove();
});

const TB = "tb_orders000001";
const opt = (id, name, color, group) => ({ id, name, color, ...(group ? { group } : {}) });
const TABLE = {
  id: TB, title: "Orders Ledger", icon: "📦", description: "Order, payment, payout, refund, and status tracking.", version: 3, updatedAt: 1, rows: 2,
  archivedAt: null,
  properties: [
    { id: "pr_name0001", name: "Order", type: "title", config: {}, position: 1 },
    { id: "pr_status01", name: "Status", type: "status", position: 2, config: { options: [
      opt("op_plan01", "Planned payment", "purple", "todo"), opt("op_refund", "Pending Refund", "orange", "doing"), opt("op_made01", "Payment Made", "blue", "done")] } },
    { id: "pr_amount01", name: "Estimate", type: "currency", config: { currency: "EUR", decimals: 2 }, position: 3 },
    { id: "pr_when0001", name: "Charge / Payout", type: "date", config: {}, position: 4 },
    { id: "pr_mail0001", name: "Email", type: "email", config: { personal: true }, position: 5 },
  ],
  views: [
    { id: "vw_table001", name: "All Accounts", layout: "table", position: 1, config: { filters: [], match: "and", sorts: [], groupBy: null, hidden: [], widths: {} } },
    { id: "vw_board001", name: "Pipeline by Status", layout: "board", position: 2, config: { filters: [], match: "and", sorts: [], groupBy: "pr_status01", hidden: ["pr_mail0001"], widths: {} } },
  ],
};
const ROWS = [
  { id: "rw_meesman001", version: 1, createdAt: 1, updatedAt: 1, hasPage: false,
    cells: { pr_name0001: "Meesman Wise", pr_status01: "op_plan01", pr_amount01: 711, pr_when0001: { start: "2026-06-01" }, pr_mail0001: "a@example.com" } },
  { id: "rw_vivid00001", version: 1, createdAt: 1, updatedAt: 1, hasPage: false,
    cells: { pr_name0001: "0142 Vivid", pr_status01: "op_refund", pr_amount01: 250, pr_when0001: { start: "2026-05-19", end: "2026-05-22" } } },
];

function rowsFor(viewId) {
  const view = TABLE.views.find((v) => v.id === viewId) ?? TABLE.views[0];
  const out = { table: TABLE, view, rows: ROWS, total: ROWS.length };
  if (view.layout === "board") {
    out.groups = [{ key: null, name: "No Status", color: "default", rowIds: [] },
      ...TABLE.properties[1].config.options.map((o) => ({ key: o.id, name: o.name, color: o.color, rowIds: ROWS.filter((r) => r.cells.pr_status01 === o.id).map((r) => r.id) }))];
  }
  return out;
}

function mount(block, routes = {}) {
  const gw = fakeGateway({
    [`GET /v1/cc/tables/${TB}/rows`]: ({ query }) => rowsFor(query.view),
    [`POST /v1/cc/tables/${TB}/rows/rw_vivid00001`]: ({ body }) => ({ ...ROWS[1], version: 2, cells: { ...ROWS[1].cells, ...body.cells } }),
    [`POST /v1/cc/tables/${TB}/rows`]: ({ body }) => ({ id: "rw_new0000001", version: 1, createdAt: 2, updatedAt: 2, hasPage: false, cells: body.cells }),
    [`GET /v1/cc/tables/${TB}/rows/rw_new0000001`]: () => ({ id: "rw_new0000001", version: 1, cells: {}, blocks: [], history: [] }),
    ...routes,
  });
  const changes = [];
  const ctx = { client: new GatewayClient({ token: "t", fetch: gw.fetch }), navigate() {}, devices: [], device: null };
  const view = createTableBlock(ctx, block, (next) => changes.push(next));
  mounted.push(view);
  document.body.append(view.element);
  return { gw, view, changes };
}

// ------------------------------------------------------------------------------------------------ pure pieces

test("dates, money and cells read like Notion", () => {
  const now = new Date("2026-09-30T12:00:00Z");
  assert.equal(formatDate({ start: "2026-06-01" }, now), "Jun 1");
  assert.equal(formatDate({ start: "2026-05-28", end: "2026-06-07" }, now), "May 28 → Jun 7");
  assert.equal(formatDate({ start: "2025-12-31T09:30" }, now), "Dec 31, 2025 09:30");
  const table = parseTable(TABLE);
  const [name, status, amount] = table.properties;
  assert.equal(formatNumber(amount, 711), "€711.00");
  assert.equal(formatNumber({ ...amount, type: "number", config: {} }, 1234567), "1,234,567");
  assert.equal(cellText(status, "op_refund"), "Pending Refund");
  assert.equal(cellText(name, "Meesman Wise"), "Meesman Wise");
  assert.equal(cellText(status, "op_gone"), "");
});

test("typed input becomes the value the gateway expects", () => {
  const table = parseTable(TABLE);
  const amount = table.properties[2];
  assert.deepEqual(parseInput(amount, "€ 1.234,50"), { value: 1234.5 });
  assert.deepEqual(parseInput(amount, "1,234.50"), { value: 1234.5 });
  assert.deepEqual(parseInput(amount, "711"), { value: 711 });
  assert.ok("error" in parseInput(amount, "a lot"));
  assert.deepEqual(parseInput(amount, "  "), { value: null });
  const email = table.properties[4];
  assert.ok("error" in parseInput(email, "not mail"));
  assert.deepEqual(parseInput({ ...email, type: "url" }, "example.com/x"), { value: "https://example.com/x" });
});

test("views: visible properties, board groups and moving a card", () => {
  const table = parseTable(TABLE);
  const board = table.views[1];
  assert.deepEqual(visibleProps(table, board).map((p) => p.name), ["Order", "Status", "Estimate", "Charge / Payout"]);
  const result = parseRows(rowsFor("vw_board001"));
  const refund = result.groups.find((g) => g.name === "Pending Refund");
  assert.deepEqual(rowsOfGroup(result, refund).map((r) => r.id), ["rw_vivid00001"]);
  const status = table.properties[1];
  assert.equal(valueForGroup(status, refund, "op_plan01"), "op_refund");
  assert.equal(valueForGroup(status, result.groups[0], "op_plan01"), null);
  const multi = { ...status, type: "multi_select" };
  assert.deepEqual(valueForGroup(multi, refund, ["op_plan01"]), ["op_refund", "op_plan01"]);
  assert.equal(valueForGroup({ ...status, type: "checkbox" }, { key: true, name: "Checked", color: "green", rowIds: [] }, false), true);
  assert.equal(nextColor([]), "gray");
  assert.deepEqual(defaultFilter(status), { property: "pr_status01", op: "is", value: "op_plan01" });
});

test("a table block is a page block, and the slash menu offers it", () => {
  assert.deepEqual(parseBlock({ id: "btab00001", type: "table", tableId: TB, viewId: "vw_board001" }), { id: "btab00001", type: "table", tableId: TB, viewId: "vw_board001" });
  assert.equal(filterSlash("table")[0].id, "table");
  const status = parseTable(TABLE).properties[1];
  const cell = renderCell(status, "op_plan01");
  assert.equal(cell.querySelector(".tb-chip").className, "tb-chip tb-status tb-c-purple");
});

// ------------------------------------------------------------------------------------------------ the component

test("the table view draws the rows the gateway computed and edits a cell in place", async () => {
  const { gw, view } = mount({ id: "btab00001", type: "table", tableId: TB, viewId: "vw_table001" });
  await flush();
  const el = view.element;
  assert.equal(el.querySelector(".tb-title").value, "Orders Ledger");
  assert.deepEqual(el.querySelectorAll(".tb-tab").slice(0, 2).map((t) => t.textContent), ["▦ All Accounts", "▥ Pipeline by Status"]);
  const rows = el.querySelectorAll(".tb-tr");
  assert.equal(rows.length, 2);
  assert.equal(rows[0].querySelector(".tb-title-text").textContent, "Meesman Wise");
  assert.equal(rows[0].querySelector("[data-prop=pr_amount01]").textContent, "€711.00");
  assert.equal(rows[1].querySelector("[data-prop=pr_when0001]").textContent.endsWith("→ May 22"), true);
  assert.equal(el.querySelector(".tb-count").textContent, "2 rows");

  const amountCell = rows[1].querySelector("[data-prop=pr_amount01]");
  amountCell.click();
  const input = amountCell.querySelector(".tb-input");
  input.value = "275";
  input.dispatchEvent({ type: "keydown", key: "Enter" });
  await flush();
  const post = gw.calls.find((c) => c.method === "POST" && c.path.endsWith("/rw_vivid00001"));
  assert.deepEqual(post.body, { cells: { pr_amount01: 275 } });
  view.destroy();
});

test("a secret typed into a cell never reaches the gateway", async () => {
  const { gw, view } = mount({ id: "btab00001", type: "table", tableId: TB, viewId: "vw_table001" });
  await flush();
  const cell = view.element.querySelectorAll(".tb-tr")[1].querySelector("[data-prop=pr_mail0001]");
  cell.click();
  const input = cell.querySelector(".tb-input");
  input.value = "password: hunter2";
  input.dispatchEvent({ type: "keydown", key: "Enter" });
  await flush();
  assert.equal(gw.calls.filter((c) => c.method === "POST").length, 0);
  assert.match(view.element.querySelector(".tb-notice").textContent, /vault/);
  view.destroy();
});

test("the board groups cards and a drop moves the card to that group", async () => {
  const { gw, view } = mount({ id: "btab00001", type: "table", tableId: TB, viewId: "vw_board001" });
  await flush();
  const cols = view.element.querySelectorAll(".tb-col");
  assert.deepEqual(cols.map((c) => c.querySelector(".tb-chip").textContent), ["Planned payment", "Pending Refund", "Payment Made"]);
  assert.equal(cols[1].querySelector(".tb-card-title").textContent, "0142 Vivid");
  assert.equal(view.element.querySelector(".tb-card .tb-link"), null, "hidden properties stay off the cards");
  const card = cols[1].querySelector(".tb-card");
  card.dispatchEvent({ type: "dragstart", dataTransfer: { setData() {} } });
  cols[2].dispatchEvent({ type: "drop", dataTransfer: { getData: () => "rw_vivid00001" } });
  await flush();
  const post = gw.calls.find((c) => c.method === "POST" && c.path.endsWith("/rw_vivid00001"));
  assert.deepEqual(post.body, { cells: { pr_status01: "op_made01" } });
  view.destroy();
});

test("New in a board column starts the row in that column", async () => {
  const { gw, view } = mount({ id: "btab00001", type: "table", tableId: TB, viewId: "vw_board001" });
  await flush();
  view.element.querySelectorAll(".tb-col")[1].querySelector(".tb-col-add").click();
  await flush();
  const post = gw.calls.find((c) => c.method === "POST" && c.path === `/v1/cc/tables/${TB}/rows`);
  assert.deepEqual(post.body, { cells: { pr_status01: "op_refund" } });
  for (const p of document.body.querySelectorAll(".tb-peek")) p.remove();
  view.destroy();
});

test("an empty table block makes a new table and remembers it", async () => {
  const created = { ...TABLE, id: TB, views: TABLE.views };
  const { gw, view, changes } = mount({ id: "btab00002", type: "table", tableId: "", viewId: null }, {
    "GET /v1/cc/tables": () => ({ tables: [{ id: "tb_other000001", title: "Brands", icon: "🏷️", rows: 4 }] }),
    "POST /v1/cc/tables": () => created,
  });
  await flush();
  assert.match(view.element.querySelector(".tb-chooser-list").textContent, /Brands/);
  view.element.querySelector(".tb-chooser .tb-primary").click();
  await flush();
  assert.ok(gw.calls.some((c) => c.method === "POST" && c.path === "/v1/cc/tables"));
  assert.deepEqual(changes[0], { id: "btab00002", type: "table", tableId: TB, viewId: "vw_table001" });
  view.destroy();
});
