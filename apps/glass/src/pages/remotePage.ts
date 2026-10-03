/**
 * Remote MCP (plan 31): the ChatGPT / Grok chat connector's HTTPS tunnel, moved from the Cyclone One window. Glass sends
 * fixed requests; the gateway runs the tunnel pack. The bearer shows only after Show token and is never stored.
 */
import type { GlassContext } from "../app.js";
import { tunnel, type TunnelMode, type TunnelStatus } from "../services/pc.js";
import { el, setChildren } from "../ui/dom.js";
import { actionButton, card, chip, errorState, keyValue, loadingState, pageHeader, segmented } from "../ui/components.js";
import { copyText } from "../ui/clipboard.js";
import type { GlassPage } from "./page.js";

export function tunnelTone(state: TunnelStatus["state"]): "success" | "warning" | "neutral" {
  return state === "running" ? "success" : state === "degraded" || state === "starting" ? "warning" : "neutral";
}

export function tunnelLabel(state: TunnelStatus["state"]): string {
  return { running: "Running", degraded: "Needs attention", starting: "Starting", stopped: "Off", unknown: "Unknown" }[state];
}

export function createRemotePage(ctx: GlassContext): GlassPage {
  const element = el("div", "page page-remote");
  const body = el("div", "pc-body");
  const notice = el("p", "pc-notice");
  let status: TunnelStatus | null = null;
  let busy = false;
  let destroyed = false;
  let docs: string | null = null;

  element.append(
    pageHeader("Remote MCP", "Let ChatGPT and Grok chat reach your phone through a private HTTPS link from this PC."),
    notice,
    body,
  );

  const say = (text: string, tone: "ok" | "error" = "ok") => {
    notice.textContent = text;
    notice.className = `pc-notice pc-notice-${tone}`;
  };

  async function run(label: string, action: () => Promise<TunnelStatus | void>): Promise<void> {
    if (busy) return;
    busy = true;
    say(`${label}…`);
    render();
    try {
      const next = await action();
      if (next) status = next;
      say(status?.message || `${label}: done.`);
    } catch (error) {
      say((error as Error).message || `${label} failed.`, "error");
    } finally {
      busy = false;
      if (!destroyed) render();
    }
  }

  function render(): void {
    if (!status) return;
    const s = status;
    const main = card("pc-card");
    main.append(el("h2", "card-title", "Tunnel"));
    main.append(
      keyValue([
        ["State", chip(tunnelLabel(s.state), tunnelTone(s.state))],
        ["MCP address for ChatGPT", s.mcpUrl ?? "Start the tunnel to get one."],
        ["Token", s.tokenLast4 ? `…${s.tokenLast4}` : "Made when the tunnel first starts"],
        ["On this PC", s.localMcpUrl],
      ]),
    );
    const mode = segmented<TunnelMode>(
      [
        { id: "readonly", label: "Read only" },
        { id: "full", label: "Full control" },
      ],
      s.mode,
      (value) => void run("Changing mode", () => tunnel.setMode(ctx.client, value)),
    );
    main.append(el("p", "muted", "Read only lets the chat look at the phone. Full control lets it act; pay, send and delete still ask you on the phone."), mode.element);

    const actions = el("div", "pc-actions");
    const running = s.state === "running" || s.state === "degraded";
    const start = actionButton(running ? "Restart" : "Start", { icon: "play", variant: "primary" });
    start.addEventListener("click", () => void run(running ? "Restarting" : "Starting", () => tunnel.start(ctx.client, s.mode)));
    const stop = actionButton("Stop", { icon: "stop" });
    stop.disabled = !running;
    stop.addEventListener("click", () => void run("Stopping", () => tunnel.stop(ctx.client)));
    const copyUrl = actionButton("Copy address", { icon: "download" });
    copyUrl.disabled = !s.mcpUrl;
    copyUrl.addEventListener("click", () => void copyText(s.mcpUrl ?? "").then((ok) => say(ok ? "Address copied." : "Your browser blocked copying; select the address instead.", ok ? "ok" : "error")));
    for (const b of [start, stop]) b.disabled = b.disabled || busy;
    actions.append(start, stop, copyUrl);
    main.append(actions);

    const secret = card("pc-card");
    secret.append(el("h2", "card-title", "Token"));
    secret.append(el("p", "muted", "Paste it into the ChatGPT or Grok connector once. Anyone with the address and token can use your phone within the mode above."));
    const reveal = el("code", "pc-code pc-token", "••••••••");
    const show = actionButton("Show and copy token", { icon: "lock" });
    show.addEventListener("click", () =>
      void run("Getting the token", async () => {
        const value = await tunnel.token(ctx.client);
        reveal.textContent = value.token;
        const ok = await copyText(value.token);
        say(ok ? "Token copied. Paste it into the connector." : "Token shown; copy it by hand.");
      }),
    );
    const rotate = actionButton("New token", { icon: "refresh", variant: "danger" });
    rotate.addEventListener("click", () => void run("Making a new token", () => tunnel.rotate(ctx.client)));
    const secretActions = el("div", "pc-actions");
    secretActions.append(show, rotate);
    secret.append(reveal, secretActions);

    const help = card("pc-card");
    help.append(el("h2", "card-title", "Check and set up"));
    const test = actionButton("Test the tunnel", { icon: "shield" });
    test.addEventListener("click", () =>
      void run("Testing", async () => {
        const result = await tunnel.smoke(ctx.client);
        say(result?.ok === false ? "The test found a problem; see Setup notes." : "The tunnel answered.", result?.ok === false ? "error" : "ok");
      }),
    );
    const notes = actionButton(docs ? "Hide setup notes" : "Setup notes", { icon: "book" });
    notes.addEventListener("click", async () => {
      if (docs) docs = null;
      else docs = await tunnel.docs(ctx.client).catch((error: Error) => `Could not load the notes: ${error.message}`);
      render();
    });
    const helpActions = el("div", "pc-actions");
    helpActions.append(test, notes);
    help.append(helpActions);
    if (docs) help.append(el("pre", "pc-code pc-docs", docs));

    setChildren(body, main, secret, help);
  }

  async function load(): Promise<void> {
    setChildren(body, loadingState("Reading the tunnel…"));
    try {
      status = await tunnel.status(ctx.client);
      if (!destroyed) render();
    } catch (error) {
      if (!destroyed) setChildren(body, errorState("The tunnel could not be read", error as { code?: string; message: string }, () => void load()));
    }
  }

  void load();
  return {
    element,
    destroy() {
      destroyed = true;
    },
  };
}
