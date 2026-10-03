/**
 * ChatGPT Attach (plan 31): VMOS cloud phones for a ChatGPT Custom GPT, moved from the Cyclone One window. Keys are
 * typed into password fields and sent once to the gateway, which protects them; Glass clears the field and keeps no
 * copy, and the gateway only ever answers whether a key is set.
 */
import type { GlassContext } from "../app.js";
import {
  CHATGPT_ATTACH_SUBTITLE,
  CUSTOM_GPT_SETUP_HINTS,
  bindOpenApiServer,
  buildFleetHandoff,
  cloudSessionReady,
  emptyChatgptAttachConfig,
  newPadDraft,
  parseVmosConnectCommand,
  readyCount,
  sanitizeError,
  type ChatgptAttachConfig,
  type ChatgptPadConfig,
  type ChatgptShareStatus,
  type ChatgptSyncResult,
} from "../core/chatgptAttach.js";
import { attach, syncFleet } from "../services/pc.js";
import { el, setChildren } from "../ui/dom.js";
import { actionButton, card, chip, errorState, loadingState, pageHeader } from "../ui/components.js";
import { copyText } from "../ui/clipboard.js";
import type { GlassPage } from "./page.js";

function field(label: string, input: HTMLInputElement | HTMLTextAreaElement): HTMLLabelElement {
  const wrap = el("label", "pc-field");
  wrap.append(el("span", "pc-field-label", label), input);
  return wrap;
}

function textInput(value: string, onInput: (value: string) => void, type = "text", placeholder = ""): HTMLInputElement {
  const input = el("input", "pc-input");
  input.type = type;
  input.value = value;
  input.placeholder = placeholder;
  input.autocomplete = "off";
  input.spellcheck = false;
  input.addEventListener("input", () => onInput(input.value));
  return input;
}

/** A password field for a key: empty keeps the saved key; what is typed goes to the gateway once. */
function secretInput(saved: boolean, onInput: (value: string) => void): HTMLInputElement {
  return textInput("", onInput, "password", saved ? "Saved. Type to replace." : "Not set");
}

export function createAttachPage(ctx: GlassContext): GlassPage {
  const element = el("div", "page page-attach");
  const notice = el("p", "pc-notice");
  const body = el("div", "pc-body");
  let config: ChatgptAttachConfig = emptyChatgptAttachConfig();
  let accessKey = "";
  const connectKeys = new Map<string, string>();
  let result: ChatgptSyncResult | null = null;
  let share: ChatgptShareStatus | null = null;
  let handoff = "";
  let busy = false;
  let destroyed = false;

  element.append(pageHeader("ChatGPT Attach", CHATGPT_ATTACH_SUBTITLE), notice, body);

  const say = (text: string, tone: "ok" | "error" = "ok") => {
    notice.textContent = text;
    notice.className = `pc-notice pc-notice-${tone}`;
  };

  async function run(label: string, action: () => Promise<void>): Promise<void> {
    if (busy) return;
    busy = true;
    say(`${label}…`);
    try {
      await action();
    } catch (error) {
      say(sanitizeError((error as Error).message) || `${label} failed.`, "error");
    } finally {
      busy = false;
      if (!destroyed) render();
    }
  }

  function outgoing(): ChatgptAttachConfig {
    const pads = config.pads.map((pad) => {
      const { hasConnectKey: _has, connectKey: _old, ...rest } = pad;
      const key = connectKeys.get(pad.id);
      return key ? { ...rest, connectKey: key } : rest;
    });
    const out: ChatgptAttachConfig = { controlApiBase: config.controlApiBase, defaultGoal: config.defaultGoal, pads };
    if (accessKey) Object.assign(out, { vmosApiKey: accessKey });
    return out;
  }

  const save = () =>
    run("Saving", async () => {
      config = await attach.save(ctx.client, outgoing());
      // The gateway holds the keys now; Glass forgets what was typed.
      accessKey = "";
      connectKeys.clear();
      say("Saved. Keys stay protected on this PC.");
    });

  function padEditor(pad: ChatgptPadConfig): HTMLElement {
    const box = el("div", "pc-pad");
    const head = el("div", "pc-pad-head");
    head.append(el("strong", undefined, pad.label || pad.id), chip(pad.hasConnectKey ? "Key saved" : "No key", pad.hasConnectKey ? "success" : "warning"));
    const remove = actionButton("Remove", { variant: "ghost" });
    remove.addEventListener("click", () => {
      config = { ...config, pads: config.pads.filter((item) => item.id !== pad.id) };
      connectKeys.delete(pad.id);
      render();
    });
    head.append(remove);
    const paste = textInput("", (value) => {
      const parsed = parseVmosConnectCommand(value);
      if (!parsed) return;
      Object.assign(pad, parsed);
      say(`Filled ${pad.label} from the Connect command. Add its Connect Key, then Save.`);
      render();
    }, "text", "Paste the VMOS Connect command (ssh … -L …)");
    const grid = el("div", "pc-grid");
    grid.append(
      field("Label", textInput(pad.label, (v) => { pad.label = v; })),
      field("SSH host", textInput(pad.sshHost, (v) => { pad.sshHost = v; })),
      field("SSH port", textInput(String(pad.sshPort), (v) => { pad.sshPort = Number(v) || 1824; }, "number")),
      field("SSH user", textInput(pad.sshUser, (v) => { pad.sshUser = v; })),
      field("Local ADB port", textInput(String(pad.localAdbPort), (v) => { pad.localAdbPort = Number(v) || 63670; }, "number")),
      field("Remote ADB", textInput(pad.remoteAdbSpec, (v) => { pad.remoteAdbSpec = v; })),
      field("Connect Key", secretInput(Boolean(pad.hasConnectKey), (v) => { if (v) connectKeys.set(pad.id, v); else connectKeys.delete(pad.id); })),
    );
    box.append(head, field("Connect command", paste), grid);
    return box;
  }

  function render(): void {
    const fleet = card("pc-card");
    fleet.append(el("h2", "card-title", "1 · Your VMOS pads"));
    fleet.append(
      field("CONTROL_API (optional stable HTTPS address)", textInput(config.controlApiBase, (v) => { config.controlApiBase = v; }, "url", "https://your-control-host/cloud")),
      field("Default goal", textInput(config.defaultGoal, (v) => { config.defaultGoal = v; })),
      field("VMOS AccessKey", secretInput(Boolean(config.hasVmosApiKey), (v) => { accessKey = v; })),
    );
    for (const pad of config.pads) fleet.append(padEditor(pad));
    const add = actionButton("Add pad", { icon: "plug" });
    add.addEventListener("click", () => {
      config = { ...config, pads: [...config.pads, newPadDraft(config.pads)] };
      render();
    });
    const saveButton = actionButton("Save", { variant: "primary" });
    saveButton.addEventListener("click", () => void save());
    const fleetActions = el("div", "pc-actions");
    fleetActions.append(add, saveButton);
    fleet.append(fleetActions);

    const syncCard = card("pc-card");
    syncCard.append(el("h2", "card-title", "2 · Sync and share"));
    const sync = actionButton("Sync fleet", { icon: "refresh", variant: "primary" });
    sync.disabled = busy || !config.pads.length;
    sync.addEventListener("click", () =>
      void run("Syncing the fleet", async () => {
        result = await syncFleet(ctx.client, globalThis.location?.origin ?? "");
        say(`${readyCount(result)} of ${result.pads.length} pads ready.`);
      }),
    );
    const shareOn = share?.running === true;
    const shareButton = actionButton(shareOn ? "Stop sharing" : "Share to ChatGPT", { icon: shareOn ? "stop" : "send" });
    shareButton.addEventListener("click", () =>
      void run(shareOn ? "Stopping the share" : "Starting the HTTPS share", async () => {
        share = shareOn ? await attach.shareStop(ctx.client) : await attach.shareStart(ctx.client);
        say(share.message || (share.running ? "Shared." : "Stopped."));
      }),
    );
    const syncActions = el("div", "pc-actions");
    syncActions.append(sync, shareButton);
    syncCard.append(syncActions);
    if (share?.url) syncCard.append(el("p", "muted", `ChatGPT reaches Cloud Control at ${share.url}. It changes each time you share.`));
    if (result) {
      const list = el("ul", "pc-list");
      for (const pad of result.pads) {
        const row = el("li", "pc-row");
        const ready = cloudSessionReady(pad);
        row.append(el("strong", undefined, pad.label), chip(ready ? "Ready" : "Not ready", ready ? "success" : "warning"),
          chip(`ADB ${pad.adb}`, pad.adb === "device" ? "success" : "neutral"), chip(`Mobile ${pad.mobile}`, pad.mobile === "running" ? "success" : "neutral"));
        if (!ready) row.append(el("span", "muted", sanitizeError(pad.error)));
        list.append(row);
      }
      syncCard.append(list);
    }

    const out = card("pc-card");
    out.append(el("h2", "card-title", "3 · Hand off to ChatGPT"));
    const build = actionButton("Build handoff", { icon: "book" });
    build.disabled = !result || !readyCount(result);
    build.addEventListener("click", () =>
      void run("Building the handoff", async () => {
        if (!result) return;
        handoff = buildFleetHandoff(result, config.defaultGoal);
        await attach.checkHandoff(ctx.client, handoff);
        say("Handoff ready. It holds session tokens only; keys never leave this PC.");
      }),
    );
    const copy = actionButton("Copy handoff", { icon: "download", variant: "primary" });
    copy.disabled = !handoff;
    copy.addEventListener("click", () => void copyText(handoff).then((ok) => say(ok ? "Copied. Paste it into your Custom GPT chat." : "Your browser blocked copying; select the text instead.", ok ? "ok" : "error")));
    const saveFile = actionButton("Save as file", { icon: "download" });
    saveFile.disabled = !handoff;
    saveFile.addEventListener("click", () => void run("Saving the handoff", async () => {
      const saved = await attach.saveHandoff(ctx.client, handoff);
      say(`Saved to ${saved.path}.`);
    }));
    const outActions = el("div", "pc-actions");
    outActions.append(build, copy, saveFile);
    out.append(outActions);
    if (handoff) out.append(el("pre", "pc-code pc-docs", handoff));

    const gpt = card("pc-card");
    gpt.append(el("h2", "card-title", "Custom GPT setup"));
    const hints = el("ol", "pc-hints");
    for (const hint of CUSTOM_GPT_SETUP_HINTS) hints.append(el("li", undefined, hint));
    const copyInstructions = actionButton("Copy instructions", { icon: "download" });
    copyInstructions.addEventListener("click", () => void run("Copying the instructions", async () => {
      const resources = await attach.resources(ctx.client);
      say((await copyText(resources.instructions)) ? "Instructions copied." : "Your browser blocked copying.");
    }));
    const copyOpenApi = actionButton("Copy OpenAPI", { icon: "download" });
    copyOpenApi.addEventListener("click", () => void run("Copying the OpenAPI schema", async () => {
      const resources = await attach.resources(ctx.client);
      const controlApi = result?.controlApi || share?.url || "";
      say((await copyText(bindOpenApiServer(resources.openapi, controlApi))) ? "OpenAPI schema copied." : "Your browser blocked copying.");
    }));
    const gptActions = el("div", "pc-actions");
    gptActions.append(copyInstructions, copyOpenApi);
    gpt.append(hints, gptActions);

    setChildren(body, fleet, syncCard, out, gpt);
  }

  async function load(): Promise<void> {
    setChildren(body, loadingState("Reading your fleet…"));
    try {
      const [loaded, shared] = await Promise.all([attach.load(ctx.client), attach.shareStatus(ctx.client).catch(() => null)]);
      config = { ...emptyChatgptAttachConfig(), ...loaded, pads: Array.isArray(loaded?.pads) ? loaded.pads : [] };
      share = shared;
      if (!destroyed) render();
    } catch (error) {
      if (!destroyed) setChildren(body, errorState("ChatGPT Attach could not load", error as { code?: string; message: string }, () => void load()));
    }
  }

  void load();
  return {
    element,
    destroy() {
      destroyed = true;
      accessKey = "";
      connectKeys.clear();
    },
  };
}
