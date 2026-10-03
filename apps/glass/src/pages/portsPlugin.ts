/**
 * Ports → one plugin (plan 48): a side drawer with its status and the one action that moves it forward, the ports it
 * serves (each with a switch), its health and checks, what it did lately, and the rarer actions: send a test, pause,
 * make a new key, remove.
 */
import type { GlassContext } from "../app.js";
import { el, setChildren } from "../ui/dom.js";
import { icon } from "../ui/icons.js";
import { relativeTime } from "../ui/format.js";
import { activityText, portLabel, ports, statusInfo, type Activity, type Plugin } from "../services/ports.js";
import { busy, checksList, keyCard, openSheet, portRow, primary, secondary, sheetHeader, statusPill, tile, toggle } from "./portsUi.js";

export interface PluginSheet {
  name: string;
  refresh(): Promise<void>;
  close(): void;
}

export function openPluginSheet(ctx: GlassContext, host: HTMLElement, name: string, onChanged: () => void, onClosed: () => void): PluginSheet {
  const sheet = openSheet(host, "drawer", name, onClosed);
  const head = el("div");
  const body = el("div", "pt-sheet-body pt-drawer-body");
  const note = el("p", "pt-note");
  note.setAttribute("role", "status");
  note.setAttribute("aria-live", "polite");
  sheet.panel.append(head, note, body);
  let plugin: Plugin | null = null;
  let activity: Activity[] = [];
  let keyShown: HTMLElement | null = null;
  let confirmRemove = false;

  const say = (text: string, tone: "ok" | "error" = "ok") => {
    note.textContent = text;
    note.classList.toggle("pt-note-error", tone === "error");
  };
  const apply = (answer: { plugin: Plugin; activity: Activity[] }) => {
    plugin = answer.plugin;
    activity = answer.activity;
    draw();
    onChanged();
  };

  async function refresh(): Promise<void> {
    try {
      const answer = await ports.plugin(ctx.client, name);
      plugin = answer.plugin;
      activity = answer.activity;
      draw();
    } catch (err) {
      say((err as Error).message, "error");
    }
  }

  function draw(): void {
    if (!plugin) return;
    const p = plugin;
    const info = statusInfo(p);
    setChildren(head, sheetHeader(p.title, p.description, sheet.close, tile(p.name, p.title, "lg")));

    // ------------------------------------------------------------------ status and its one action
    const status = el("section", `pt-status pt-status-${info.tone}`);
    const statusText = el("div", "pt-status-text");
    statusText.append(statusPill(p), el("p", undefined, info.explain));
    status.append(statusText);
    if (info.action) {
      const action = primary(info.actionLabel);
      action.addEventListener("click", () => void runAction(info.action!, action));
      status.append(action);
    }

    // ------------------------------------------------------------------ change to review
    const review = p.pending ? reviewBox(p) : null;

    // ------------------------------------------------------------------ ports
    const portsBox = el("section", "pt-block");
    portsBox.append(blockTitle("Ports", `${p.serves.filter((s) => s.allowed).length} of ${p.serves.length} allowed`));
    const list = el("div", "pt-port-list");
    for (const served of p.serves) {
      const control = toggle(served.allowed, `Allow ${portLabel(served.port)}`, async (on) => {
        try {
          apply(await ports.setPort(ctx.client, p.name, served.port, on));
          say(on ? `${portLabel(served.port)} allowed.` : `${portLabel(served.port)} switched off.`);
        } catch (err) {
          say((err as Error).message, "error");
          throw err;
        }
      });
      list.append(portRow(served, control));
    }
    portsBox.append(list);

    // ------------------------------------------------------------------ health and details
    const details = el("section", "pt-block");
    details.append(blockTitle("Details", ""));
    const kv = el("dl", "pt-kv");
    const rows: Array<[string, string | Node]> = [
      ["Address", el("code", "pt-code", p.endpoint)],
      ["Version", p.version || "—"],
      ["Last seen", p.seenAt ? relativeTime(p.seenAt) : "not yet"],
      ["Checks", p.checks ? `${p.checks.total - p.checks.failed} of ${p.checks.total} passed, ${relativeTime(p.checkedAt)}` : "not run yet"],
      ["Key", `${p.kid}, sealed on this PC`],
    ];
    if (p.features.length) rows.push(["Features", p.features.join(", ")]);
    if (p.remote) rows.push(["Runs on", "another computer (https)"]);
    for (const [k, v] of rows) {
      kv.append(el("dt", undefined, k));
      const dd = el("dd");
      if (typeof v === "string") dd.textContent = v;
      else dd.append(v);
      kv.append(dd);
    }
    details.append(kv);

    // ------------------------------------------------------------------ checks
    const checks = el("section", "pt-block");
    if (p.checks?.items.length) {
      const failed = p.checks.items.filter((c) => !c.ok).length;
      checks.append(blockTitle("Checks", failed ? `${failed} of ${p.checks.total} need attention` : `checked ${relativeTime(p.checkedAt)}`), checksList(p.checks.items));
    }

    // ------------------------------------------------------------------ activity
    const recent = el("section", "pt-block");
    recent.append(blockTitle("Recent", ""));
    if (activity.length) {
      const feed = el("ol", "pt-feed pt-feed-compact");
      for (const a of activity.slice(0, 12)) feed.append(feedRow(a));
      recent.append(feed);
    } else {
      recent.append(el("p", "pt-fine", "Nothing yet."));
    }

    // ------------------------------------------------------------------ actions
    const actions = el("section", "pt-block");
    actions.append(blockTitle("Actions", ""));
    const row = el("div", "pt-actions");
    const test = secondary("Send a test", "send");
    test.disabled = p.status !== "active" && p.status !== "unreachable";
    test.title = test.disabled ? "Only a plugin that passed its checks gets messages." : "Send a signed test message to the plugin.";
    test.addEventListener("click", async () => {
      const r = await busy(test, "Sending…", () => ports.test(ctx.client, p.name), say);
      if (r) {
        apply(r);
        say(r.ok ? `Delivered to ${portLabel(r.port)} in ${r.latencyMs ?? 0} ms.` : `The plugin didn't take it (${r.status ? `HTTP ${r.status}` : "no answer"}).`, r.ok ? "ok" : "error");
      }
    });
    const check = secondary("Run checks", "refresh");
    check.addEventListener("click", async () => {
      const r = await busy(check, "Checking…", () => ports.check(ctx.client, p.name), say);
      if (r) {
        apply(r);
        say(r.plugin.status === "active" ? "All checks passed." : "Some checks need attention.", r.plugin.status === "active" ? "ok" : "error");
      }
    });
    const pause = secondary(p.paused ? "Resume" : "Pause", p.paused ? "play" : "pause");
    pause.addEventListener("click", async () => {
      const r = await busy(pause, p.paused ? "Resuming…" : "Pausing…", () => ports.pause(ctx.client, p.name, !p.paused), say);
      if (r) apply(r);
    });
    const key = secondary("New key", "key");
    key.addEventListener("click", () => void makeKey(key));
    row.append(test, check, pause, key);
    actions.append(row);

    const danger = el("div", "pt-danger");
    if (confirmRemove) {
      const really = secondary(`Remove ${p.title}`, "trash", "danger");
      really.addEventListener("click", async () => {
        const r = await busy(really, "Removing…", () => ports.remove(ctx.client, p.name), say);
        if (r !== null) {
          onChanged();
          sheet.close();
        }
      });
      const keep = secondary("Keep it", undefined, "ghost");
      keep.addEventListener("click", () => {
        confirmRemove = false;
        draw();
      });
      danger.append(el("p", undefined, "It stops getting anything at once, and its key is deleted."), keep, really);
    } else {
      const remove = secondary("Remove plugin", "trash", "ghost");
      remove.classList.add("pt-remove");
      remove.addEventListener("click", () => {
        confirmRemove = true;
        draw();
      });
      danger.append(remove);
    }
    actions.append(danger);

    setChildren(body, status, review, keyShown, portsBox, checks, details, recent, actions);
  }

  function reviewBox(p: Plugin): HTMLElement {
    const box = el("section", "pt-callout pt-callout-warning pt-review-change");
    const d = p.pending!;
    const text = el("div");
    text.append(el("strong", undefined, `${p.title} changed what it asks for`));
    const list = el("ul", "pt-problems");
    if (d.version) list.append(el("li", undefined, `Version ${d.version}`));
    for (const port of d.added) list.append(el("li", undefined, `Wants ${portLabel(port)} (${port}). It starts switched off.`));
    for (const port of d.removed) list.append(el("li", undefined, `No longer serves ${portLabel(port)}.`));
    for (const f of d.featuresAdded) list.append(el("li", undefined, `New feature: ${f}`));
    if (d.endpointChanged) list.append(el("li", undefined, "Names a different address."));
    if (!list.children.length) list.append(el("li", undefined, "Its manifest changed."));
    text.append(list);
    const approve = primary("Approve change", "check");
    approve.addEventListener("click", async () => {
      const r = await busy(approve, "Approving…", () => ports.approve(ctx.client, p.name), say);
      if (r) {
        apply(r);
        say("Approved. The checks ran again with the new manifest.");
      }
    });
    box.append(icon("alert"), text, approve);
    return box;
  }

  async function makeKey(trigger: HTMLButtonElement): Promise<void> {
    const r = await busy(trigger, "Making…", () => ports.newKey(ctx.client, name), say);
    if (!r) return;
    keyShown = el("section", "pt-block");
    keyShown.append(keyCard(r.plugin.title, r.key, (t) => say(t)),
      el("p", "pt-fine", "The old key stopped working. Restart the plugin with this one, then run the checks."));
    plugin = r.plugin;
    draw();
    onChanged();
  }

  async function runAction(action: "checks" | "review" | "resume" | "key", trigger: HTMLButtonElement): Promise<void> {
    if (!plugin) return;
    if (action === "checks") {
      const r = await busy(trigger, "Checking…", () => ports.check(ctx.client, name), say);
      if (r) {
        apply(r);
        say(r.plugin.status === "active" ? `${r.plugin.title} is live.` : "Some checks need attention.", r.plugin.status === "active" ? "ok" : "error");
      }
    } else if (action === "resume") {
      const r = await busy(trigger, "Resuming…", () => ports.pause(ctx.client, name, false), say);
      if (r) apply(r);
    } else if (action === "key") {
      await makeKey(trigger);
    } else {
      body.querySelector(".pt-review-change")?.scrollIntoView?.({ behavior: "smooth", block: "center" });
    }
  }

  void refresh();
  return { name, refresh, close: sheet.close };
}

function blockTitle(title: string, aside: string): HTMLElement {
  const node = el("div", "pt-block-head");
  node.append(el("h3", "pt-block-title", title));
  if (aside) node.append(el("span", "pt-block-aside", aside));
  return node;
}

export function feedRow(a: Activity, showPlugin = false, title = a.plugin): HTMLElement {
  const row = el("li", `pt-feed-row ${a.ok ? "ok" : "bad"}`);
  const dot = el("span", "pt-feed-dot");
  const text = el("span", "pt-feed-text");
  if (showPlugin) text.append(el("strong", "pt-feed-who", title));
  text.append(el("span", undefined, activityText(a)));
  const meta = el("span", "pt-feed-meta", [a.latencyMs != null && a.kind !== "check" ? `${a.latencyMs} ms` : "", relativeTime(a.at)].filter(Boolean).join(" · "));
  row.append(dot, text, meta);
  return row;
}
