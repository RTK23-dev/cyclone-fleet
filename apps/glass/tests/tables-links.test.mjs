import test from "node:test";
import assert from "node:assert/strict";
import { installMiniDom } from "./helpers/mini-dom.mjs";
import { fakeGateway, flush } from "./helpers/fakeGateway.mjs";
import { GatewayClient } from "../.test-dist/services/gateway.js";
import {
  addDays, calendarDays, computedText, daysBetween, effectiveType, linkLabels, parseRows, parseTable, spanOf,
} from "../.test-dist/services/tables.js";
import { createTableBlock } from "../.test-dist/workspace/tableView.js";

installMiniDom();
const mounted = [];
test.afterEach(() => {
  while (mounted.length) mounted.pop().destroy();
  for (const p of document.body.querySelectorAll(".tb-peek")) p.remove();
});

const ORDERS = "tb_orders000001";
const BANKS = "tb_banks0000001";
const view = (id, name, layout, extra = {}) => ({ id, name, layout, position: 1,
  config: { filters: [], match: "and", sorts: [], groupBy: null, hidden: [], widths: {}, dateProp: null, ...extra } });
const TABLE = {
  id: ORDERS, title: "Orders", icon: "📦", description: "", version: 1, updatedAt: 1, rows: 2, archivedAt: null,
  properties: [
    { id: "pr_name0001", name: "Order", type: "title", config: {}, position: 1 },
    { id: "pr_bank0001", name: "Bank", type: "relation", config: { target: BANKS, backProp: "pr_back0001" }, position: 2 },
    { id: "pr_when0001", name: "Charge", type: "date", config: {}, position: 3 },
    { id: "pr_vat00001", name: "With VAT", type: "formula", config: { expression: 'prop("Estimate") * 1.21' }, position: 4 },
  ],
  views: [view("vw_table001", "All", "table"), view("vw_tl000001", "Timeline", "timeline", { dateProp: "pr_when0001" }),
    view("vw_cal00001", "Calendar", "calendar", { dateProp: "pr_when0001" }), view("vw_gal00001", "Gallery", "gallery")],
};
const ROWS = [
  { id: "rw_meesman001", version: 1, createdAt: 1, updatedAt: 1, hasPage: false,
    cells: { pr_name0001: "Meesman", pr_bank0001: ["rw_wise0000001"], pr_when0001: { start: "2026-06-01", end: "2026-06-03" }, pr_vat00001: 860.31 } },
  { id: "rw_bux00000001", version: 1, createdAt: 1, updatedAt: 1, hasPage: false,
    cells: { pr_name0001: "Bux", pr_when0001: { start: "2026-06-10" } } },
];
const LINKS = { rw_wise0000001: { label: "Wise ES | 002", tableId: BANKS } };
const BANK_TABLE = {
  id: BANKS, title: "Banks", icon: "🏦", description: "", version: 1, updatedAt: 1, rows: 1, archivedAt: null, views: [view("vw_bank0001", "All", "table")],
  properties: [{ id: "pr_bname001", name: "Name", type: "title", config: {}, position: 1 },
    { id: "pr_back0001", name: "Orders", type: "relation", config: { target: ORDERS, backProp: "pr_bank0001" }, position: 2 },
    { id: "pr_total001", name: "Total", type: "rollup", config: { relation: "pr_back0001", property: "pr_x", fn: "sum", resultType: "number" }, position: 3 }],
};

function mount(viewId, routes = {}) {
  const gw = fakeGateway({
    [`GET /v1/cc/tables/${ORDERS}/rows`]: ({ query }) => ({ table: TABLE, view: TABLE.views.find((v) => v.id === query.view) ?? TABLE.views[0], rows: ROWS, total: 2, links: LINKS }),
    [`GET /v1/cc/tables/${BANKS}/rows/rw_wise0000001`]: () => ({ id: "rw_wise0000001", version: 3, cells: { pr_bname001: "Wise ES | 002", pr_back0001: ["rw_meesman001"], pr_total001: 1361 },
      blocks: [], history: [], links: { rw_meesman001: { label: "Meesman", tableId: ORDERS } }, table: BANK_TABLE }),
    [`GET /v1/cc/tables/${ORDERS}/properties/pr_bank0001/candidates`]: () => ({ items: [
      { id: "rw_wise0000001", label: "Wise ES | 002", tableId: BANKS }, { id: "rw_bunq0000001", label: "Bunq ES | 001", tableId: BANKS }] }),
    [`POST /v1/cc/tables/${ORDERS}/rows/rw_bux00000001`]: ({ body }) => ({ ...ROWS[1], version: 2, cells: { ...ROWS[1].cells, ...body.cells } }),
    ...routes,
  });
  const ctx = { client: new GatewayClient({ token: "t", fetch: gw.fetch }), navigate() {}, devices: [], device: null };
  const v = createTableBlock(ctx, { id: "btab00001", type: "table", tableId: ORDERS, viewId: viewId }, () => {});
  mounted.push(v);
  document.body.append(v.element);
  return { gw, v };
}

test("dates for timelines and calendars", () => {
  const june = calendarDays(2026, 5);
  assert.equal(june.length, 42);
  assert.equal(june[0], "2026-06-01", "June 2026 starts on a Monday");
  assert.equal(calendarDays(2026, 4)[0], "2026-04-27");
  assert.deepEqual(spanOf({ start: "2026-05-28", end: "2026-06-07" }), { start: "2026-05-28", end: "2026-06-07" });
  assert.deepEqual(spanOf({ start: "2026-06-01T09:30" }), { start: "2026-06-01", end: "2026-06-01" });
  assert.equal(spanOf(null), null);
  assert.equal(daysBetween("2026-05-28", "2026-06-07"), 10);
  assert.equal(addDays("2026-05-31", 1), "2026-06-01");
});

test("rollups, formulas and links read well", () => {
  const banks = parseTable(BANK_TABLE);
  const total = banks.properties[2];
  assert.equal(effectiveType(total), "number");
  assert.equal(computedText(total, 1361), "1,361");
  assert.equal(computedText({ ...total, config: { ...total.config, fn: "percent_checked" } }, 50), "50%");
  assert.equal(computedText(total, { start: "2026-05-28" }).startsWith("May 28"), true);
  assert.equal(computedText(total, true), "Yes");
  assert.deepEqual(linkLabels(["rw_wise0000001", "rw_gone"], LINKS).map((l) => l.label), ["Wise ES | 002", "Removed"]);
  assert.deepEqual(parseRows({ links: LINKS }).links, LINKS);
});

test("a linked bank reads by name and opens its own row, with its orders", async () => {
  const { gw, v } = mount("vw_table001");
  await flush();
  const chip = v.element.querySelector(".tb-link-chip");
  assert.equal(chip.textContent, "↗ Wise ES | 002");
  assert.equal(v.element.querySelector("[data-prop=pr_vat00001]").textContent, "860.31");
  chip.click();
  await flush();
  assert.ok(gw.calls.some((c) => c.path === `/v1/cc/tables/${BANKS}/rows/rw_wise0000001`));
  const peek = document.body.querySelector(".tb-peek");
  assert.equal(peek.querySelector(".tb-peek-title").value, "Wise ES | 002");
  assert.equal(peek.querySelector(".tb-peek-from").textContent, "🏦 Banks");
  assert.match(peek.querySelector(".tb-peek-props").textContent, /Meesman/);
  assert.match(peek.querySelector(".tb-peek-props").textContent, /1,361/);
});

test("linking a row picks from the other table", async () => {
  const { gw, v } = mount("vw_table001");
  await flush();
  const cell = v.element.querySelectorAll(".tb-tr")[1].querySelector("[data-prop=pr_bank0001]");
  cell.click();
  await flush();
  const choices = cell.querySelectorAll(".tb-choice");
  assert.deepEqual(choices.map((c) => c.textContent), ["Wise ES | 002", "Bunq ES | 001"]);
  choices[1].click();
  await flush();
  cell.querySelector(".tb-primary").click();
  await flush();
  const post = gw.calls.find((c) => c.method === "POST" && c.path.endsWith("/rw_bux00000001"));
  assert.deepEqual(post.body, { cells: { pr_bank0001: ["rw_bunq0000001"] } });
});

test("the timeline draws a bar from start to end", async () => {
  const { v } = mount("vw_tl000001");
  await flush();
  const bars = v.element.querySelectorAll(".tb-tl-bar");
  assert.equal(bars.length, 2);
  assert.equal(bars[0].style["--tb-len"], "3", "June 1 to June 3 is three days");
  assert.equal(Number(bars[1].style["--tb-from"]) - Number(bars[0].style["--tb-from"]), 9);
});

test("the calendar shows rows on their days and gallery shows cards", async () => {
  const { v } = mount("vw_cal00001");
  await flush();
  const now = new Date();
  for (let i = 0; i < (now.getFullYear() - 2026) * 12 + now.getMonth() - 5; i += 1) {
    v.element.querySelectorAll(".tb-ghost").find((b) => b.title === "Previous month").click();
  }
  assert.match(v.element.querySelector(".tb-cal-title").textContent, /2026/);
  const items = v.element.querySelectorAll(".tb-cal-item").map((i) => i.textContent);
  assert.ok(items.includes("Meesman") && items.includes("Bux"));
  const { v: gallery } = mount("vw_gal00001");
  await flush();
  assert.deepEqual(gallery.element.querySelectorAll(".tb-gallery-card .tb-card-title").map((t) => t.textContent), ["Meesman", "Bux"]);
});

test("a new relation asks where it points before it is made", async () => {
  const { gw, v } = mount("vw_table001", {
    "GET /v1/cc/tables": () => ({ tables: [{ id: BANKS, title: "Banks", icon: "🏦", rows: 1 }] }),
    [`POST /v1/cc/tables/${ORDERS}/properties`]: () => TABLE,
  });
  await flush();
  v.element.querySelector(".tb-th-add .tb-th-button").click();
  const relation = v.element.querySelectorAll(".tb-menu-row").find((b) => b.textContent.includes("Relation"));
  relation.click();
  await flush();
  const select = v.element.querySelector(".tb-pop select");
  assert.deepEqual(select.children.map((o) => o.value), [BANKS, "sys:accounts", "sys:routines", "sys:tasks", "sys:phones"]);
  select.value = BANKS;
  v.element.querySelector(".tb-pop .tb-primary").click();
  await flush();
  const post = gw.calls.find((c) => c.method === "POST" && c.path === `/v1/cc/tables/${ORDERS}/properties`);
  assert.deepEqual(post.body, { name: "Relation", type: "relation", config: { target: BANKS, twoWay: true } });
});
