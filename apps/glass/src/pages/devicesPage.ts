/**
 * Devices: the place to connect and disconnect phones, like linking WhatsApp Web.
 *
 * Connect: Glass asks the phone through the gateway, the phone shows "Connect this PC?" with the same six-digit
 * code, the user taps Allow on the phone. Disconnect: the PC forgets the phone and tells the phone to forget it.
 * The phone and the gateway own the trust; this page only starts requests, waits and shows state.
 */
import type { GlassContext } from "../app.js";
import { deviceReadiness, fixConnection, type ConnectionActionKind, type GlassDevice } from "../services/devices.js";
import { GatewayError } from "../services/gateway.js";
import {
  beginConnect,
  disconnect,
  formatMatchCode,
  scanForPhones,
  waitForPhone,
  type ConnectOutcome,
} from "../services/pairing.js";
import { actionButton, chip, emptyState, pageHeader, type Tone } from "../ui/components.js";
import { el, setChildren } from "../ui/dom.js";
import { icon } from "../ui/icons.js";
import { deviceGate } from "./deviceGate.js";
import { connectionLine } from "./connectionLine.js";
import { createCloudPhonesView, type CloudViewDeps } from "./cloudPhonesView.js";
import { createPhoneCareView, type PhoneCareDeps, type PhoneCareView } from "./phoneCareView.js";
import type { GlassPage } from "./page.js";

type Flow =
  | { step: "starting" }
  | { step: "waiting"; code: string | null; startedAt: number }
  | { step: "connected" }
  | { step: "declined" | "expired" }
  | { step: "failed"; message: string }
  | { step: "confirm-disconnect" }
  | { step: "disconnecting" };

export interface DevicesPageDeps {
  now(): number;
  sleep(ms: number): Promise<void>;
  /** Phone care timing (tests pass a controlled clock). */
  care?: PhoneCareDeps;
  /** Cloud phones timing (tests pass a controlled clock). */
  cloud?: CloudViewDeps;
}

const defaultDeps: DevicesPageDeps = {
  now: () => Date.now(),
  sleep: (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
};

export function createDevicesPage(initial: GlassContext, deps: DevicesPageDeps = defaultDeps): GlassPage & { update(ctx: GlassContext): void } {
  let ctx = initial;
  let destroyed = false;
  let scanning = false;
  const flows = new Map<string, Flow>();
  // One phone care view per reachable phone, kept across renders so its state and polling survive.
  const cares = new Map<string, PhoneCareView>();
  const careFor = (device: GlassDevice): HTMLElement => {
    let view = cares.get(device.id);
    if (!view) {
      view = createPhoneCareView(ctx.client, device.id, deps.care);
      cares.set(device.id, view);
    }
    return view.element;
  };
  // Plan 44: the cloud accounts and the phones this PC keeps connected; a phone that connects shows up in the list.
  const cloud = createCloudPhonesView(initial.client, { ...(deps.cloud ?? {}), later: deps.cloud?.later ?? laterUnref,
                                                         onChange: () => void ctx.refreshDevices() });
  const element = el("div", "page page-devices");
  const header = el("div");
  const body = el("div", "devices-body");
  element.append(header, body);

  const setFlow = (id: string, flow: Flow | null): void => {
    if (destroyed) return;
    if (flow) flows.set(id, flow);
    else flows.delete(id);
    render();
  };

  const connect = async (device: GlassDevice): Promise<void> => {
    setFlow(device.id, { step: "starting" });
    let startedAt = deps.now();
    try {
      const begun = await beginConnect(ctx.client, device.id);
      if (begun.sessionReady) {
        // Already trusted: begin only reopened the session.
        await finish(device.id, { kind: "connected", status: begun });
        return;
      }
      startedAt = deps.now();
      setFlow(device.id, { step: "waiting", code: begun.matchCode, startedAt });
    } catch (error) {
      setFlow(device.id, { step: "failed", message: failureText(error) });
      return;
    }
    const outcome = await waitForPhone(ctx.client, device.id, startedAt, {
      now: deps.now,
      sleep: deps.sleep,
      isCancelled: () => destroyed || flows.get(device.id)?.step !== "waiting",
    });
    await finish(device.id, outcome);
  };

  const finish = async (id: string, outcome: ConnectOutcome): Promise<void> => {
    if (destroyed || outcome.kind === "cancelled") return;
    if (outcome.kind === "connected") {
      setFlow(id, { step: "connected" });
      ctx.selectDevice(id);
      await ctx.refreshDevices();
      return;
    }
    if (outcome.kind === "failed") setFlow(id, { step: "failed", message: failureText(outcome.error) });
    else setFlow(id, { step: outcome.kind });
  };

  const forget = async (device: GlassDevice): Promise<void> => {
    setFlow(device.id, { step: "disconnecting" });
    try {
      await disconnect(ctx.client, device.id);
      setFlow(device.id, null);
      await ctx.refreshDevices();
    } catch (error) {
      setFlow(device.id, { step: "failed", message: failureText(error) });
    }
  };

  /** The connection line's one action: connect, install (phone care), or one of the gateway's fixed repairs. */
  const act = async (device: GlassDevice, kind: ConnectionActionKind): Promise<void> => {
    if (kind === "connect") return connect(device);
    if (kind === "install") {
      careFor(device);
      await cares.get(device.id)?.update();
      return;
    }
    try {
      await fixConnection(ctx.client, device.id, kind);
    } catch (error) {
      setFlow(device.id, { step: "failed", message: failureText(error) });
      return;
    }
    await ctx.refreshDevices();
  };

  const scan = async (): Promise<void> => {
    scanning = true;
    render();
    try {
      await scanForPhones(ctx.client);
    } catch {
      /* the device list below shows the gateway state */
    }
    scanning = false;
    await ctx.refreshDevices();
    render();
  };

  function render(): void {
    const look = actionButton(scanning ? "Looking…" : "Look for phones", { icon: "refresh" });
    look.disabled = scanning;
    look.addEventListener("click", () => void scan());
    setChildren(header, pageHeader("Devices", "Connect a phone to Glass: Glass shows a code, you tap Allow on the phone. Disconnect any time.", [look]));

    if (ctx.devicesError) {
      setChildren(body, deviceGate(ctx));
      return;
    }
    const connected = ctx.devices.filter((device) => device.paired);
    const available = ctx.devices.filter((device) => !device.paired && reachable(device));
    const unreachable = ctx.devices.filter((device) => !device.paired && !reachable(device));

    const listed = new Set([...connected, ...available].map((device) => device.id));
    for (const [id, view] of cares) {
      if (!listed.has(id)) {
        view.destroy();
        cares.delete(id);
      }
    }
    const sections: HTMLElement[] = [];
    if (connected.length) sections.push(section("Connected", connected.map(connectedCard)));
    if (available.length) sections.push(section("Ready to connect", available.map(availableCard)));
    if (unreachable.length) sections.push(section("Needs the phone", unreachable.map(unreachableCard)));
    if (!sections.length) {
      sections.push(
        emptyState({
          icon: "phone",
          title: "No phone found",
          body: "Plug the phone into this PC by USB and allow USB debugging on the phone. Cyclone on the phone must be open once. Then press Look for phones.",
        }),
      );
    }
    sections.push(cloud.element, howItWorks());
    setChildren(body, ...sections);
  }

  function connectedCard(device: GlassDevice): HTMLElement {
    const readiness = deviceReadiness(device);
    const flow = flows.get(device.id);
    const tone: Tone = readiness.ready ? "success" : readiness.reason === "disconnected" ? "danger" : "warning";
    const status = readiness.ready ? "Connected" : readiness.reason === "disconnected" ? "Phone offline" : readiness.reason === "needs-update" ? "Update Cyclone" : "Reconnecting";
    const card = deviceCard(device, chip(status, tone));
    const line = connectionLine(device.connection, (kind) => act(device, kind));
    if (line) card.append(line);
    else if (!readiness.ready) card.append(el("p", "device-problem", readiness.message));
    card.append(careFor(device));

    const actions = el("div", "device-actions");
    if (flow?.step === "confirm-disconnect") {
      actions.append(el("span", "device-confirm", `Disconnect ${device.name}? Glass and Cyclone One lose access until you connect again.`));
      const yes = actionButton("Disconnect", { variant: "danger" });
      yes.addEventListener("click", () => void forget(device));
      const no = actionButton("Keep", { variant: "ghost" });
      no.addEventListener("click", () => setFlow(device.id, null));
      actions.append(yes, no);
    } else if (flow?.step === "disconnecting") {
      actions.append(el("span", "device-confirm", "Disconnecting…"));
    } else {
      if (readiness.ready) {
        const use = actionButton(device.id === ctx.device?.id ? "In use" : "Use in Glass", { variant: device.id === ctx.device?.id ? "ghost" : "primary" });
        use.disabled = device.id === ctx.device?.id;
        use.addEventListener("click", () => {
          ctx.selectDevice(device.id);
          ctx.navigate({ name: "apps" });
        });
        actions.append(use);
      } else if (readiness.reason === "connecting") {
        const again = actionButton("Reconnect", { icon: "refresh", variant: "primary" });
        again.addEventListener("click", () => void connect(device));
        actions.append(again);
      }
      const off = actionButton("Disconnect", { variant: "ghost" });
      off.addEventListener("click", () => setFlow(device.id, { step: "confirm-disconnect" }));
      actions.append(off);
    }
    card.append(actions);
    if (flow && flow.step !== "confirm-disconnect" && flow.step !== "disconnecting") card.append(flowPanel(device, flow));
    return card;
  }

  function availableCard(device: GlassDevice): HTMLElement {
    const flow = flows.get(device.id);
    const card = deviceCard(device, chip("Not connected", "neutral"));
    // "Connect this phone" is the card's own button; any other broken link (Cyclone stopped, the PC already asked
    // on the phone) shows as the connection line.
    const verdict = device.connection && device.connection.code !== "TRUST_NEEDED" ? device.connection : null;
    const line = flow ? null : connectionLine(verdict, (kind) => act(device, kind));
    if (line) card.append(line);
    // An out-of-date phone app can be updated before connecting (it may be why connecting fails).
    card.append(careFor(device));
    const askedOnPhone = verdict?.code === "TRUST_CONFIRM";
    if (!askedOnPhone && (!flow || flow.step === "declined" || flow.step === "expired" || flow.step === "failed")) {
      const actions = el("div", "device-actions");
      const go = actionButton(flow ? "Try again" : "Connect", { icon: "plug", variant: "primary" });
      go.addEventListener("click", () => void connect(device));
      actions.append(go);
      card.append(actions);
    }
    if (flow) card.append(flowPanel(device, flow));
    return card;
  }

  function unreachableCard(device: GlassDevice): HTMLElement {
    const card = deviceCard(device, chip(device.state === "UNAUTHORIZED" ? "Allow USB debugging" : "Offline", "warning"));
    const line = connectionLine(device.connection, (kind) => act(device, kind));
    if (line) {
      card.append(line);
      return card;
    }
    card.append(
      el(
        "p",
        "device-problem",
        device.state === "UNAUTHORIZED"
          ? "The phone asks \"Allow USB debugging?\". Tap Allow (tick \"Always allow from this computer\"), then it shows up here."
          : "The phone is not reachable over USB right now. Check the cable, unlock the phone, then press Look for phones.",
      ),
    );
    return card;
  }

  function flowPanel(device: GlassDevice, flow: Flow): HTMLElement {
    const panel = el("div", `connect-panel connect-${flow.step}`);
    panel.setAttribute("role", "status");
    switch (flow.step) {
      case "starting":
        panel.append(el("span", "spinner"), el("span", undefined, `Asking ${device.name}…`));
        break;
      case "waiting": {
        const code = el("div", "connect-code", flow.code ? formatMatchCode(flow.code) : "······");
        const steps = el("ol", "connect-steps");
        steps.append(
          el("li", undefined, `On ${device.name}, open the "Connect ${device.pcLabel ?? "this PC"}?" notification (or Cyclone → Settings → PC Gateway).`),
          el("li", undefined, "Check the phone shows the same code."),
          el("li", undefined, "Tap Allow. Unlock the phone first if it is locked."),
        );
        const cancel = actionButton("Cancel", { variant: "ghost" });
        cancel.addEventListener("click", () => setFlow(device.id, null));
        const waiting = el("div", "connect-waiting");
        waiting.append(el("span", "spinner"), el("span", undefined, "Waiting for the phone…"));
        panel.append(code, steps, waiting, cancel);
        break;
      }
      case "connected":
        panel.append(icon("plug"), el("span", undefined, `${device.name} is connected. Glass can now show its apps, runs and screen.`));
        break;
      case "declined":
        panel.append(el("span", undefined, "The phone answered Not now. Nothing was connected."));
        break;
      case "expired":
        panel.append(el("span", undefined, "No answer from the phone in time. Try again and tap Allow on the phone."));
        break;
      case "failed":
        panel.append(el("span", undefined, flow.message));
        break;
      default:
        break;
    }
    return panel;
  }

  render();
  return {
    element,
    update(next) {
      ctx = next;
      // A phone that became connected elsewhere (Cyclone One, the phone) needs no finished flow note forever.
      for (const [id, flow] of flows) {
        if (flow.step === "connected" && !ctx.devices.some((device) => device.id === id && device.paired)) flows.delete(id);
      }
      render();
    },
    destroy() {
      destroyed = true;
      flows.clear();
      for (const view of cares.values()) view.destroy();
      cares.clear();
      cloud.destroy();
    },
  };
}

function laterUnref(fn: () => void, ms: number): () => void {
  const id = setTimeout(fn, ms);
  (id as unknown as { unref?: () => void }).unref?.();
  return () => clearTimeout(id);
}

function section(title: string, cards: HTMLElement[]): HTMLElement {
  const node = el("section", "devices-section");
  const list = el("div", "devices-grid");
  list.append(...cards);
  node.append(el("h2", "section-title", title), list);
  return node;
}

function deviceCard(device: GlassDevice, status: HTMLElement): HTMLElement {
  const card = el("article", "card device-card");
  card.dataset.deviceId = device.id;
  const top = el("div", "device-card-top");
  const badge = el("div", "device-badge");
  badge.append(icon("phone"));
  const names = el("div", "device-names");
  const meta = [device.model && device.model !== device.name ? device.model : "", transportLabel(device.transport), device.mobileVersion ? `Cyclone ${device.mobileVersion}` : ""]
    .filter(Boolean)
    .join(" · ");
  names.append(el("span", "device-name", device.name), el("span", "muted", meta));
  if (device.paired && device.pcLabel) names.append(el("span", "muted device-linked-as", `The phone lists this PC as “${device.pcLabel}”`));
  top.append(badge, names, status);
  card.append(top);
  return card;
}

function howItWorks(): HTMLElement {
  const node = el("section", "card devices-help");
  node.append(el("h2", "card-title", "How connecting works"));
  const list = el("ul", "devices-help-list");
  list.append(
    el("li", undefined, "The phone does the thinking. Glass only shows what the phone knows and sends what you do."),
    el("li", undefined, "A connected phone stays connected when you unplug it; plug it back in and it picks up again."),
    el("li", undefined, "Disconnect here, or on the phone in Cyclone → Settings → PC Gateway. Pay, send, delete and sign-in steps still ask on the phone."),
  );
  node.append(list);
  return node;
}

function reachable(device: GlassDevice): boolean {
  return device.state !== "DISCONNECTED" && device.state !== "UNAUTHORIZED";
}

function transportLabel(transport: string): string {
  if (transport === "LAN") return "Wi-Fi";
  if (transport === "VIRTUAL") return "Virtual phone";
  if (transport === "CLOUD") return "Cloud phone";
  return "USB";
}

function failureText(error: unknown): string {
  if (error instanceof GatewayError) {
    if (error.code === "PHONE_LOCKED") return "Unlock the phone, then try again.";
    if (error.code === "TRUST_REJECTED") return "The phone answered Not now. Nothing was connected.";
    return error.message || "The phone did not answer.";
  }
  return error instanceof Error ? error.message : String(error);
}
