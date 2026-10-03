/**
 * Phone: watch the phone live, take control from the PC, give it back, and give Cyclone a goal.
 * Every tap goes to the phone's PhoneToolExecutor through the gateway; GATE and PHONE_LOCKED still apply.
 */
import type { GlassContext } from "../app.js";
import { sendControl, type ControlBody } from "../services/control.js";
import { FOREGROUND_SESSION_ID, phoneClient } from "../services/phone.js";
import { getHere } from "../services/knowledge.js";
import { appName, roomLabel } from "../services/runs.js";
import { el, setChildren } from "../ui/dom.js";
import { actionButton, chip, pageHeader } from "../ui/components.js";
import { createLiveView, type LiveView, type LiveViewOptions } from "../ui/liveView.js";
import { createAskPanel, type AskPanelDeps } from "./askPanel.js";
import { listRuns, normalizeGoal } from "../services/runs.js";
import { deviceGate } from "./deviceGate.js";
import { liveViewProblem } from "../services/devices.js";
import { keepBackgroundOn } from "../services/background.js";
import type { GlassPage } from "./page.js";

export interface PhonePageDeps {
  origin?: string;
  fetch?: typeof fetch;
  rendererFactory?: LiveViewOptions["rendererFactory"];
  askTimer?: Pick<AskPanelDeps, "setTimer" | "clearTimer">;
  /** "You are here" refresh; defaults to every 3 s. */
  hereTimer?: { setInterval(fn: () => void, ms: number): unknown; clearInterval(handle: unknown): void };
}

type Owner = "AI" | "HUMAN";

export function createPhonePage(ctx: GlassContext, deps: PhonePageDeps = {}): GlassPage {
  const element = el("div", "page page-phone");
  element.append(pageHeader("Phone", "Watch the phone, take control, or give Cyclone a goal."));
  const gate = deviceGate(ctx);
  if (gate || !ctx.device) {
    element.append(gate ?? el("div"));
    return { element, destroy() {} };
  }
  const device = ctx.device;
  let owner: Owner = "AI";
  let takeover: Promise<boolean> | null = null;
  let gestureQueue = Promise.resolve();

  const note = el("p", "control-note");
  note.setAttribute("role", "status");
  const run = async (body: ControlBody): Promise<boolean> => {
    try {
      const result = await sendControl(ctx.client, device.id, body);
      if (result.inputOwner) owner = result.inputOwner;
      if (!result.ok) note.textContent = controlCopy(result.verification);
      else note.textContent = "";
      return result.ok;
    } catch (error) {
      note.textContent = error instanceof Error ? error.message : String(error);
      return false;
    }
  };

  const live: LiveView = createLiveView({
    client: ctx.client,
    deviceId: device.id,
    origin: deps.origin ?? globalThis.location?.origin ?? "http://127.0.0.1:8765",
    rendererFactory: deps.rendererFactory,
    unavailableMessage: (state) => {
      const current = ctx.devices.find((d) => d.id === device.id) ?? device;
      // While reconnecting with USB fine, "Reconnecting…" alone is the truth; otherwise say what is wrong.
      return state === "RECONNECTING" && current.usb === "USB_AUTHORIZED" ? null : liveViewProblem(current);
    },
    onGesture: (gesture) => {
      // A click on the live phone is a request for human control. Keep that first
      // gesture and send it after the handoff, in pointer order.
      gestureQueue = gestureQueue.then(async () => {
        if (owner !== "HUMAN" && !(await takeControl())) return;
        if (gesture.type === "tap") await run({ kind: "tap", x: gesture.x, y: gesture.y });
        else await run({ kind: "swipe", x1: gesture.x, y1: gesture.y, x2: gesture.x2 ?? gesture.x, y2: gesture.y2 ?? gesture.y, duration_ms: gesture.durationMs ?? 300 });
      });
    },
  });

  const ownerChip = el("span", "owner-chip");
  const controls = el("div", "phone-controls");
  const keys = el("div", "phone-keys");
  const back = actionButton("Back", { icon: "back" });
  const home = actionButton("Home", { icon: "phone" });
  const wake = actionButton("Wake", { icon: "refresh", variant: "ghost" });
  back.addEventListener("click", () => void run({ kind: "back" }));
  home.addEventListener("click", () => void run({ kind: "home" }));
  wake.addEventListener("click", () => void run({ kind: "wake" }));
  const scrollUp = actionButton("Scroll up", { variant: "ghost" });
  const scrollDown = actionButton("Scroll down", { variant: "ghost" });
  scrollUp.addEventListener("click", () => void run({ kind: "scroll_up" }));
  scrollDown.addEventListener("click", () => void run({ kind: "scroll_down" }));
  keys.append(back, home, scrollUp, scrollDown, wake);

  // Typing from the PC while you have control. Sent once to the focused field on the phone, then cleared; never stored.
  const typing = el("form", "phone-type");
  const typeInput = el("input", "phone-type-input");
  typeInput.type = "text";
  typeInput.placeholder = "Type into the focused field on the phone";
  typeInput.setAttribute("aria-label", "Type on the phone");
  typeInput.setAttribute("autocomplete", "off");
  typeInput.maxLength = 4096;
  const typeSend = actionButton("Type", { icon: "send" });
  typeSend.type = "submit";
  typing.append(typeInput, typeSend);
  const typeNote = el("p", "muted phone-type-note", "Passwords belong in the phone's Secrets Card, not here.");
  typing.addEventListener("submit", (event) => {
    event.preventDefault?.();
    const text = typeInput.value;
    if (!text || owner !== "HUMAN") return;
    typeInput.value = "";
    void run({ kind: "text", text });
  });

  const render = (): void => {
    const human = owner === "HUMAN";
    setChildren(ownerChip, chip(human ? "You have control" : "Cyclone has control", human ? "warning" : "success"));
    const toggle = actionButton(human ? "Give back to Cyclone" : "Take control", { icon: "hand", variant: human ? "secondary" : "primary" });
    toggle.addEventListener("click", () => void (human ? giveBack() : takeControl()));
    setChildren(controls, toggle);
    live.setInteractive(true);
    for (const key of [back, home, scrollUp, scrollDown, typeSend]) key.disabled = !human;
    typeInput.disabled = !human;
    typing.hidden = !human;
    typeNote.hidden = !human;
  };

  const takeControl = async (): Promise<boolean> => {
    if (owner === "HUMAN") return true;
    if (takeover) return takeover;
    takeover = run({ kind: "take_human", sessionId: FOREGROUND_SESSION_ID }).then((ok) => {
      if (ok) owner = "HUMAN";
      render();
      return ok;
    }).finally(() => { takeover = null; });
    return takeover;
  };

  const giveBack = async (): Promise<void> => {
    if (await run({ kind: "yield_ai", sessionId: FOREGROUND_SESSION_ID })) {
      owner = "AI";
    }
    render();
  };

  const ask = createAskPanel({
    phone: phoneClient(ctx, device.id, deps.fetch),
    latestRun: async (since) => {
      const runs = await listRuns(ctx.client, device.id, "all", 5);
      const fresh = runs.filter((run) => run.startedAt >= since).sort((a, b) => b.startedAt - a.startedAt);
      return fresh[0]?.runId ?? null;
    },
    recentGoals: async () => {
      const runs = await listRuns(ctx.client, device.id, "all", 40);
      const seen = new Set<string>();
      const goals: string[] = [];
      for (const run of [...runs].sort((a, b) => b.startedAt - a.startedAt)) {
        const key = normalizeGoal(run.goal);
        if (!key || run.model === "cyclone-mapper" || seen.has(key)) continue;
        seen.add(key);
        goals.push(run.goal);
      }
      return goals;
    },
    ...deps.askTimer,
  });

  const stage = el("section", "phone-stage");
  const bar = el("div", "phone-bar");
  // Wi-Fi screen share (AnyDesk-style): the phone streams its own screen; Glass only asks, the owner taps on the phone.
  const shareBox = el("div", "share-box");
  let sharing = false;
  let shareAsked = false;
  const renderShare = (): void => {
    if (sharing) {
      setChildren(shareBox, chip("Wi‑Fi share on", "success"));
      return;
    }
    const shareButton = actionButton(shareAsked ? "Asked, tap the notification on the phone" : "Share over Wi‑Fi", { icon: "phone", variant: "ghost" });
    shareButton.title = "Asks the phone to share its screen with this PC over Wi‑Fi. Nothing starts until you tap the notification on the phone.";
    shareButton.disabled = shareAsked;
    shareButton.addEventListener("click", async () => {
      shareButton.disabled = true;
      try {
        const reply = await ctx.client.post<{ prompted?: boolean; sharing?: boolean }>(`/v1/devices/${encodeURIComponent(device.id)}/share/request`);
        sharing = reply?.sharing === true;
        shareAsked = !sharing;
      } catch {
        shareAsked = false;
        shareButton.title = "This phone cannot share over Wi‑Fi yet. Update Cyclone on the phone.";
      }
      renderShare();
    });
    setChildren(shareBox, shareButton);
  };
  const refreshShare = async (): Promise<void> => {
    try {
      const status = await ctx.client.get<{ sharing?: boolean }>(`/v1/devices/${encodeURIComponent(device.id)}/share/status`);
      const now = status?.sharing === true;
      if (now !== sharing) {
        sharing = now;
        if (now) shareAsked = false;
        renderShare();
      }
    } catch {
      /* older phones: keep the button; it explains itself when pressed */
    }
  };
  renderShare();
  bar.append(ownerChip, shareBox, controls);
  stage.append(bar, live.element, keys, typing, typeNote, note);
  const here = el("section", "card here-card");
  here.setAttribute("role", "status");
  here.hidden = true;
  let hereKey = "";
  const refreshHere = async (): Promise<void> => {
    try {
      const now = await getHere(ctx.client, device.id);
      const key = `${now.placeId}|${now.roomId}`;
      if (key === hereKey) return;
      hereKey = key;
      here.hidden = false;
      if (!now.placeId) {
        setChildren(here, el("span", "cause-kicker", "You are here"), el("p", "muted", "Not inside an app Cyclone can map (home screen or system screen)."));
        return;
      }
      const facts = el("p", "here-facts");
      facts.append(el("strong", undefined, appName(now.placeId)), el("span", "muted", now.appVersion ? ` · version ${now.appVersion}` : ""));
      const parts: HTMLElement[] = [el("span", "cause-kicker", "You are here"), facts];
      if (now.roomId) {
        parts.push(el("p", "muted", roomLabel(now.roomId)));
        const show = actionButton("Show on the map", { icon: "map" });
        show.addEventListener("click", () => ctx.navigate({ name: "app", placeId: now.placeId!, tab: "map", route: [now.roomId!] }));
        parts.push(show);
      }
      setChildren(here, ...parts);
    } catch {
      here.hidden = true; // phones before alpha.12 have no atlas.here
    }
  };
  const hereTimer = deps.hereTimer ?? { setInterval: (fn: () => void, ms: number) => setInterval(fn, ms), clearInterval: (h: unknown) => clearInterval(h as number) };
  const hereHandle = hereTimer.setInterval(() => {
    void refreshHere();
    void refreshShare();
  }, 3_000);
  void refreshHere();
  void refreshShare();

  // Plan 26 (A42-1): background work that comes back by itself after a phone restart.
  const background = el("section", "card background-card");
  const backgroundNote = el("p", "muted", "Cyclone's background work needs a helper that stops when the phone restarts. Keep it on once from here.");
  backgroundNote.setAttribute("role", "status");
  const keepOn = actionButton("Keep background work on");
  keepOn.addEventListener("click", () => {
    keepOn.disabled = true;
    backgroundNote.textContent = "Asking the phone…";
    keepBackgroundOn(ctx.client, device.id)
      .then((result) => { backgroundNote.textContent = result.message; })
      .catch((error) => { backgroundNote.textContent = error instanceof Error ? error.message : String(error); })
      .finally(() => { keepOn.disabled = false; });
  });
  background.append(el("span", "cause-kicker", "Background work"), backgroundNote, keepOn);

  const side = el("div", "phone-side");
  side.append(here, background, ask.element);
  const layout = el("div", "phone-layout");
  layout.append(stage, side);
  element.append(layout);
  render();

  return {
    element,
    destroy() {
      live.destroy();
      ask.destroy();
      hereTimer.clearInterval(hereHandle);
    },
  };
}

export function controlCopy(code: string): string {
  switch (code) {
    case "PHONE_LOCKED":
      return "The phone is locked. Unlock it on the phone; Glass never unlocks it for you.";
    case "HUMAN_HAS_CONTROL":
      return "Someone else has control of this phone right now.";
    default:
      return `The phone did not accept that (${code}).`;
  }
}
