/**
 * Ports → Add plugin (plan 48): one guided sheet in four steps.
 *   1. Address: where the plugin runs.
 *   2. Review: what it is and the ports it serves, each with a switch. Personal and secret ports start on for a
 *      plugin on this PC and off for a remote one.
 *   3. Key: shown once, with the line to set it, while the plugin restarts.
 *   4. Checks: the hub runs the contract's checks against the plugin; it is live only when they pass.
 */
import type { GlassContext } from "../app.js";
import { el, setChildren } from "../ui/dom.js";
import { icon } from "../ui/icons.js";
import { ports, STARTERS, portLabel, type KeyCard, type Plugin, type Preview } from "../services/ports.js";
import { busy, checksList, keyCard, openSheet, portRow, primary, secondary, sheetHeader, tile, toggle } from "./portsUi.js";

const STEPS = ["Address", "Review", "Key", "Checks"] as const;

export function openAddPlugin(ctx: GlassContext, host: HTMLElement, options: { endpoint?: string; onChanged: () => void }): void {
  const sheet = openSheet(host, "modal", "Add a plugin", options.onChanged);
  const steps = el("ol", "pt-steps");
  const body = el("div", "pt-sheet-body");
  const foot = el("footer", "pt-sheet-foot");
  const note = el("p", "pt-note");
  note.setAttribute("role", "status");
  note.setAttribute("aria-live", "polite");
  sheet.panel.append(sheetHeader("Add a plugin", "Plug a tool into Cyclone runs. It gets only the ports you allow.", sheet.close), steps, body, note, foot);

  const say = (text: string, tone: "ok" | "error" = "ok") => {
    note.textContent = text;
    note.classList.toggle("pt-note-error", tone === "error");
  };
  const drawSteps = (at: number) => {
    setChildren(steps, ...STEPS.map((label, i) => {
      const item = el("li", `pt-step${i < at ? " done" : i === at ? " now" : ""}`);
      const dot = el("span", "pt-step-dot");
      if (i < at) dot.append(icon("check"));
      else dot.textContent = String(i + 1);
      item.append(dot, el("span", "pt-step-label", label));
      if (i === at) item.setAttribute("aria-current", "step");
      return item;
    }));
  };

  // ---------------------------------------------------------------------------------------------- 1. address
  const address = el("input", "cc-input pt-address");
  address.type = "url";
  address.placeholder = "http://127.0.0.1:8771";
  address.setAttribute("aria-label", "Plugin address");
  address.autocomplete = "off";
  address.spellcheck = false;
  address.value = options.endpoint ?? "";

  function stepAddress(): void {
    drawSteps(0);
    say("");
    const lookUp = primary("Look it up");
    const go = async () => {
      if (!address.value.trim()) {
        say("Enter the address the plugin prints when it starts.", "error");
        address.focus();
        return;
      }
      const preview = await busy(lookUp, "Looking…", () => ports.preview(ctx.client, address.value), say);
      if (preview) stepReview(preview);
    };
    lookUp.addEventListener("click", () => void go());
    address.onkeydown = (event: KeyboardEvent) => {
      if (event.key === "Enter") void go();
    };
    const starters = el("div", "pt-starters");
    for (const s of STARTERS) {
      const chip = el("button", "pt-starter");
      chip.type = "button";
      chip.append(el("span", "pt-starter-title", s.title), el("span", "pt-starter-addr", s.endpoint.replace("http://", "")));
      chip.addEventListener("click", () => {
        address.value = s.endpoint;
        address.focus();
      });
      starters.append(chip);
    }
    const field = el("label", "pt-field");
    field.append(el("span", "pt-field-label", "Where does the plugin run?"), address);
    setChildren(body, field,
      el("p", "pt-fine", "A plugin on this PC uses http://127.0.0.1 and a port. A plugin elsewhere needs https."),
      el("div", "pt-field-label", "The kit's example plugins"), starters);
    const cancel = secondary("Cancel", undefined, "ghost");
    cancel.addEventListener("click", sheet.close);
    setChildren(foot, cancel, lookUp);
    setTimeout(() => address.focus?.(), 30);
  }

  // ---------------------------------------------------------------------------------------------- 2. review
  function stepReview(preview: Preview): void {
    drawSteps(1);
    say("");
    const allowed = new Set(preview.serves.filter((s) => s.allowed).map((s) => s.port));
    const name = preview.manifest.name ?? "plugin";
    const title = preview.manifest.title || name;
    const card = el("div", "pt-review");
    const head = el("div", "pt-review-head");
    const titles = el("div", "pt-review-titles");
    titles.append(el("h3", "pt-review-title", title));
    const meta = el("div", "pt-meta");
    meta.append(el("span", undefined, preview.manifest.version ? `Version ${preview.manifest.version}` : ""), el("code", "pt-code", preview.endpoint));
    if (preview.remote) meta.append(el("span", "pt-remote", "Remote"));
    titles.append(meta);
    head.append(tile(name, title, "lg"), titles);
    card.append(head);
    if (preview.manifest.description) card.append(el("p", "pt-review-about", preview.manifest.description));

    const back = secondary("Back", undefined, "ghost");
    back.addEventListener("click", stepAddress);

    if (preview.problems.length || preview.alreadyAdded) {
      const box = el("div", "pt-callout pt-callout-danger");
      box.append(icon("alert"));
      const text = el("div");
      if (preview.alreadyAdded) {
        text.append(el("strong", undefined, `${title} is already added.`), el("p", undefined, "Open it from the Ports page to change it."));
      } else {
        text.append(el("strong", undefined, "This plugin doesn't follow cyclone.ports/1 yet."));
        const list = el("ul", "pt-problems");
        for (const p of preview.problems.slice(0, 6)) list.append(el("li", undefined, p));
        text.append(list);
      }
      box.append(text);
      setChildren(body, card, box);
      setChildren(foot, back);
      return;
    }

    const list = el("div", "pt-port-list");
    for (const served of preview.serves) {
      const control = toggle(allowed.has(served.port), `Allow ${portLabel(served.port)}`, (on) => {
        if (on) allowed.add(served.port);
        else allowed.delete(served.port);
      });
      list.append(portRow(served, control));
    }
    const intro = el("p", "pt-fine", preview.remote
      ? "This plugin runs on another computer, so personal and secret ports start off. Switch on only what it needs."
      : "Switch off anything it shouldn't get. You can change this any time.");
    const add = primary("Add plugin", "plus");
    add.addEventListener("click", async () => {
      const result = await busy(add, "Adding…", () => ports.add(ctx.client, preview.endpoint, [...allowed]), say);
      if (result) {
        options.onChanged();
        stepKey(result.plugin, result.key);
      }
    });
    setChildren(body, card, el("h4", "pt-section-title", "Ports it serves"), intro, list);
    if (preview.endpointDiffers) body.append(el("p", "pt-fine", "Its manifest names another address. Cyclone will use the one you entered."));
    setChildren(foot, back, add);
  }

  // ---------------------------------------------------------------------------------------------- 3. key
  function stepKey(plugin: Plugin, key: KeyCard): void {
    drawSteps(2);
    say("");
    const steps3 = el("ol", "pt-howto");
    steps3.append(
      el("li", undefined, "Copy the key, or the line for your shell."),
      el("li", undefined, "Stop the plugin and start it again with the key set."),
      el("li", undefined, "Come back and run the checks."),
    );
    const run = primary("I've restarted it, run the checks", "check");
    run.addEventListener("click", () => void stepChecks(plugin, run));
    const later = secondary("Do it later", undefined, "ghost");
    later.addEventListener("click", sheet.close);
    setChildren(body, keyCard(plugin.title, key, (t) => say(t)), steps3);
    setChildren(foot, later, run);
  }

  // ---------------------------------------------------------------------------------------------- 4. checks
  async function stepChecks(plugin: Plugin, trigger: HTMLButtonElement): Promise<void> {
    const answer = await busy(trigger, "Checking…", () => ports.check(ctx.client, plugin.name), say);
    if (!answer) return;
    drawSteps(3);
    say("");
    options.onChanged();
    const result = answer.plugin;
    const passed = result.status === "active";
    const hero = el("div", `pt-result ${passed ? "pass" : "fail"}`);
    const badge = el("span", "pt-result-badge");
    badge.append(icon(passed ? "check" : "alert"));
    const words = el("div");
    words.append(
      el("h3", "pt-result-title", passed ? `${result.title} is live` : result.status === "waiting_key" ? `${result.title} doesn't have its key yet` : `${result.title} needs a fix`),
      el("p", "pt-result-body", passed
        ? `It passed all ${result.checks?.total ?? 0} checks. Runs can now use the ports you allowed.`
        : result.status === "waiting_key"
          ? "Set the key where the plugin starts, restart it, and run the checks again."
          : "Each failed check below says what to change. Fix it, restart the plugin, and run the checks again."),
    );
    hero.append(badge, words);
    const again = secondary("Run checks again", "refresh");
    again.addEventListener("click", () => void stepChecks(plugin, again));
    const done = primary(passed ? "Done" : "Close");
    done.addEventListener("click", sheet.close);
    setChildren(body, hero, checksList(result.checks?.items ?? [], { reveal: true }));
    setChildren(foot, ...(passed ? [done] : [again, done]));
  }

  stepAddress();
}
