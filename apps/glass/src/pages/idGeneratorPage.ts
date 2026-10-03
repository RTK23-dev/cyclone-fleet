/** Native Ports starter. Studio remains the rendering engine; Glass owns consent and agent usage. */
import type { GlassContext } from "../app.js";
import type { GlassPage } from "./page.js";
import { el, link } from "../ui/dom.js";
import { actionButton, card, pageHeader } from "../ui/components.js";
import { copyText } from "../ui/clipboard.js";
import { idGenerator, studioFrame, type IdStarter, type IdUsage } from "../services/idGenerator.js";

export function createIdGeneratorPage(ctx: GlassContext): GlassPage {
  const element = el("div", "page page-ports page-id-generator");
  const back = link("All Ports", "#/command/ports", "btn btn-secondary");
  const refresh = actionButton("Check Studio", { icon: "refresh" });
  element.append(pageHeader("ID Generator", "Generate company employee IDs with your local MRZ Studio. Set the defaults in Studio and decide how Cyclone agents may use it.", [back, refresh]));
  const note = el("p", "pt-note"); note.setAttribute("role", "status"); note.setAttribute("aria-live", "polite");
  const connection = card("idg-section");
  const state = el("h2", "idg-state", "Looking for Studio…");
  const detail = el("p", "pt-note");
  const api = input("Studio API address", "", "Auto: Studio settings, then http://127.0.0.1:8787");
  const permissions = el("fieldset", "idg-permissions");
  permissions.append(el("legend", undefined, "Allow ID Generator to receive and return"));
  const consent = new Map<string, HTMLInputElement>();
  for (const [port, label] of [["file.out", "Employee portrait"], ["x.id-generator.generate", "Employee details and generation requests"],
    ["value.in", "Generation status and MRZ results"], ["file.in", "Generated ID files"]]) {
    const field = toggle(label, false); consent.set(port, field.input); permissions.append(field.element);
  }
  const connect = actionButton("Connect Studio", { variant: "primary" });
  const usage = card("idg-section"); usage.append(el("h2", undefined, "Agent usage"));
  const enabled = toggle("Let agents choose ID Generator when a request matches", true);
  const when = text("When to use", 1000);
  const instructions = text("Your workflow instructions", 4000);
  const apps = input("Allowed apps", "", "Package IDs, separated by commas. Empty means all apps.");
  const routines = input("Allowed routines", "", "Routine IDs, separated by commas. Empty means all routines.");
  const save = actionButton("Save usage settings", { variant: "primary" });
  usage.append(enabled.element, when.element, instructions.element, apps.element, routines.element,
    el("p", "pt-note", "If both lists are filled, both must match. Turning agent usage off keeps the manual Studio panel available. Ports permissions and the Port map still apply."), save);
  const studio = card("idg-section");
  const generate = actionButton("Generate an ID", { variant: "primary" });
  const defaults = actionButton("Studio defaults");
  const copy = actionButton("Copy agent guide");
  const schema = actionButton("View request schema");
  const toolbar = el("div", "idg-actions"); toolbar.append(generate, defaults, copy, schema);
  const frameHost = el("div", "idg-frame-host");
  const reference = el("pre", "idg-reference"); reference.hidden = true;
  studio.append(el("h2", undefined, "Company IDs"),
    el("p", "pt-note", "Studio owns MRZ and number rules, preset or custom cities, height, Paul Signature and other fonts, portrait crop and background removal. Exporting requires your templates and an operable Photoshop host."), toolbar, frameHost, reference);
  connection.append(state, detail, api.element, permissions, connect);
  element.append(note, connection, usage, studio);

  let data: IdStarter | null = null, initialized = false, destroyed = false, busy = false, loading = false;
  let activePanel: "generate" | "defaults" = "generate", currentFrame = "";
  const say = (message: string, error = false) => { note.textContent = message; note.classList.toggle("pt-note-error", error); };
  const values = (s: string) => s.split(/[\n,]/).map(x => x.trim()).filter(Boolean);
  const config = (): IdUsage => ({ version: 1, apiBase: api.input.value.trim(), agentEnabled: enabled.input.checked,
    apps: values(apps.input.value), routines: values(routines.input.value), whenToUse: when.input.value, instructions: instructions.input.value });

  function render(next: IdStarter): void {
    data = next;
    if (!initialized) {
      const c = next.config;
      api.input.value = c.apiBase; enabled.input.checked = c.agentEnabled;
      when.input.value = c.whenToUse; instructions.input.value = c.instructions;
      apps.input.value = c.apps.join(", "); routines.input.value = c.routines.join(", ");
      for (const [port, checkbox] of consent) checkbox.checked = next.plugin?.serves.find(s => s.port === port)?.allowed ?? false;
      initialized = true;
    }
    state.textContent = next.state === "found" ? (next.plugin?.status === "active" ? "Studio connected" : "Studio found on this PC") : "Studio unavailable";
    detail.textContent = `${next.detail} Address: ${next.apiBase}.` +
      (next.plugin && next.plugin.status !== "active" ? ` Plugin: ${next.plugin.status.replaceAll("_", " ")}. Review its card in All Ports if needed.` : "") +
      (next.health.dryRun ? " Dry-run mode: files are placeholders." : "") + (!next.health.worker ? " Worker is not online." : "");
    connect.disabled = busy || next.state !== "found" || next.health.busy;
    paintFrame();
  }
  function paintFrame(): void {
    if (!data) return;
    const path = activePanel === "generate" ? "/plugins/id-generator" : "/settings/id-generator";
    const raw = activePanel === "generate" ? data.panelUrl : data.settingsUrl;
    const url = data.plugin?.status === "active" ? studioFrame(raw, path, globalThis.location?.origin) : null;
    generate.disabled = defaults.disabled = !url && data.plugin?.status !== "active";
    if (url === currentFrame) return;
    currentFrame = url ?? "";
    frameHost.replaceChildren();
    if (!url) { frameHost.append(el("p", "pt-note", "Start Studio, allow its four ports, and connect it to open the generator here.")); return; }
    const frame = el("iframe", "idg-frame");
    frame.title = activePanel === "generate" ? "MRZ Studio ID Generator" : "MRZ Studio ID Generator defaults";
    frame.setAttribute("sandbox", "allow-scripts allow-same-origin allow-forms allow-downloads");
    frame.referrerPolicy = "no-referrer"; frame.src = url; frameHost.append(frame);
  }
  async function action(fn: () => Promise<void>): Promise<void> {
    if (busy) return;
    busy = true; connect.disabled = save.disabled = true;
    try { await fn(); } catch (e) { if (!destroyed) say((e as Error).message, true); }
    finally { busy = false; if (!destroyed) { save.disabled = false; if (data) render(data); } }
  }
  async function load(force = false): Promise<void> {
    if (destroyed || loading || busy) return;
    loading = true;
    try { const next = await idGenerator.status(ctx.client, force); if (!destroyed) render(next); }
    catch (e) { if (!destroyed) say((e as Error).message, true); }
    finally { loading = false; }
  }
  refresh.addEventListener("click", () => void load(true));
  save.addEventListener("click", () => void action(async () => {
    const next = await idGenerator.save(ctx.client, config()); if (!destroyed) { render(next); say("Usage settings saved."); }
  }));
  connect.addEventListener("click", () => void action(async () => {
    await idGenerator.save(ctx.client, config());
    const next = await idGenerator.connect(ctx.client, [...consent].filter(([, c]) => c.checked).map(([p]) => p));
    if (!destroyed) { render(next); say(next.plugin?.status === "active" ? "Studio paired and checks passed." : "Studio paired. Its checks need attention; open All Ports for details."); }
  }));
  generate.addEventListener("click", () => { activePanel = "generate"; paintFrame(); });
  defaults.addEventListener("click", () => { activePanel = "defaults"; paintFrame(); });
  copy.addEventListener("click", () => void action(async () => {
    if (!data) return;
    const c = data.config;
    const guide = `# ID Generator\n\nWhen to use: ${c.whenToUse}\n\n${data.skill.workflow}\n\nDeveloper guidance: ${c.instructions}\nApps: ${c.apps.join(", ") || "all"}\nRoutines: ${c.routines.join(", ") || "all"}`;
    const copied = await copyText(guide);
    if (!destroyed) {
      if (!copied) { reference.textContent = guide; reference.hidden = false; }
      say(copied ? "Agent guide copied." : "Clipboard unavailable. Select and copy the guide below.");
    }
  }));
  schema.addEventListener("click", () => void action(async () => {
    const value = await idGenerator.schema(ctx.client);
    if (!destroyed) { reference.textContent = JSON.stringify(value, null, 2); reference.hidden = false; }
  }));
  void load();
  const timer = setInterval(() => void load(), 10_000);
  return { element, destroy() { destroyed = true; clearInterval(timer); frameHost.replaceChildren(); } };
}

function input(label: string, value: string, hint: string) {
  const element = el("label", "idg-field"), field = el("input", "input");
  field.value = value; field.placeholder = hint; field.setAttribute("aria-label", label);
  element.append(el("span", undefined, label), field, el("small", "pt-note", hint));
  return { element, input: field };
}
function text(label: string, limit: number) {
  const element = el("label", "idg-field"), field = el("textarea", "input");
  field.maxLength = limit; field.rows = 3; field.setAttribute("aria-label", label);
  element.append(el("span", undefined, label), field); return { element, input: field };
}
function toggle(label: string, checked: boolean) {
  const element = el("label", "idg-toggle"), field = el("input");
  field.type = "checkbox"; field.checked = checked; field.setAttribute("aria-label", label);
  element.append(field, el("span", undefined, label)); return { element, input: field };
}
