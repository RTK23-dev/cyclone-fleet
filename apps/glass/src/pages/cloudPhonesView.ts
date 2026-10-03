/**
 * Cloud phones on the Devices page (plan 44, alpha 90): add a VMOS Cloud, DuoPlus or remote-ADB account, pick the
 * phones this PC keeps connected, and see each one's link in one line. Once connected a cloud phone shows up above
 * with the other phones: connecting, installing Cyclone and phone care work as they do for a USB phone.
 */
import { actionButton, chip, segmented, type Tone } from "../ui/components.js";
import { el, setChildren } from "../ui/dom.js";
import { GatewayError, type GatewayClient } from "../services/gateway.js";
import { cloudApi, settling, type CloudAccount, type CloudPhone, type CloudProvider, type CloudStatus } from "../services/cloud.js";

export interface CloudViewDeps {
  /** Schedules the next look; returns a cancel function. */
  later(fn: () => void, ms: number): () => void;
  /** A phone just connected or let go: the device list above should look again. */
  onChange?(): void;
}

const defaultDeps: CloudViewDeps = {
  later: (fn, ms) => {
    const id = setTimeout(fn, ms);
    (id as unknown as { unref?: () => void }).unref?.();
    return () => clearTimeout(id);
  },
};

/** While a link is on its way, look often; otherwise now and then (renewals and drops show on their own). */
export const SETTLING_POLL_MS = 3_000;
export const IDLE_POLL_MS = 30_000;

const PROVIDERS: Array<{ id: CloudProvider; label: string }> = [
  { id: "vmos", label: "VMOS Cloud" },
  { id: "duoplus", label: "DuoPlus" },
  { id: "adb", label: "Remote ADB" },
];

const FIELDS: Record<CloudProvider, Array<{ key: string; label: string; secret: boolean; placeholder: string }>> = {
  vmos: [
    { key: "accessKey", label: "Access key", secret: false, placeholder: "From VMOS Cloud → API" },
    { key: "secretKey", label: "Secret key", secret: true, placeholder: "Kept encrypted on this PC" },
  ],
  duoplus: [{ key: "apiKey", label: "API key", secret: true, placeholder: "From DuoPlus → Automation → API" }],
  adb: [{ key: "address", label: "ADB address", secret: false, placeholder: "203.0.113.7:5555" }],
};

export interface CloudPhonesView {
  element: HTMLElement;
  refresh(): Promise<void>;
  destroy(): void;
}

export function createCloudPhonesView(client: GatewayClient, deps: CloudViewDeps = defaultDeps): CloudPhonesView {
  const element = el("section", "devices-section cloud");
  let status: CloudStatus | null = null;
  let adding = false;
  let provider: CloudProvider = "vmos";
  let formError: string | null = null;
  let busy = false;
  let destroyed = false;
  let cancel: (() => void) | null = null;
  const confirming = new Set<string>();
  const phoneErrors = new Map<string, string>();
  let connected = new Set<string>();

  const schedule = (): void => {
    cancel?.();
    if (destroyed) return;
    cancel = deps.later(() => void refresh(), status && settling(status) ? SETTLING_POLL_MS : IDLE_POLL_MS);
  };

  async function refresh(): Promise<void> {
    try {
      status = await cloudApi.status(client);
    } catch (error) {
      // An older runtime without cloud phones: show nothing.
      if (error instanceof GatewayError && error.status === 404) {
        status = null;
        element.hidden = true;
        return;
      }
    }
    const now = new Set(status?.accounts.flatMap((a) => a.phones.filter((p) => p.state === "connected").map((p) => `${a.id}/${p.remoteId}`)) ?? []);
    if ([...now].some((key) => !connected.has(key)) || [...connected].some((key) => !now.has(key))) deps.onChange?.();
    connected = now;
    render();
    schedule();
  }

  const act = async (fn: () => Promise<unknown>, key?: string): Promise<void> => {
    busy = true;
    if (key) phoneErrors.delete(key);
    render();
    try {
      await fn();
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      if (key) phoneErrors.set(key, message);
      else formError = message;
    }
    busy = false;
    await refresh();
  };

  function render(): void {
    if (destroyed) return;
    element.hidden = status === null;
    if (!status) return;
    const add = actionButton(adding ? "Cancel" : "Add cloud phones", { variant: "ghost", icon: adding ? undefined : "plug" });
    add.addEventListener("click", () => {
      adding = !adding;
      formError = null;
      render();
    });
    const head = el("div", "cloud-head");
    head.append(el("h2", "section-title", "Cloud phones"), add);
    const parts: HTMLElement[] = [head];
    if (adding) parts.push(form());
    else if (!status.accounts.length) {
      parts.push(el("p", "device-problem", "Connect VMOS Cloud or DuoPlus phones to this PC. Cyclone keeps each one connected, renews its access before it runs out and installs Cyclone on it."));
    }
    if (status.memoryOnly && status.accounts.length) {
      parts.push(el("p", "device-problem", "On this system the accounts are kept only until Cyclone restarts."));
    }
    if (!status.ssh && status.accounts.some((a) => a.provider === "vmos")) {
      parts.push(el("p", "cloud-warn", "VMOS phones need Windows' OpenSSH Client: Settings → System → Optional features → OpenSSH Client."));
    }
    const grid = el("div", "devices-grid");
    grid.append(...status.accounts.map(accountCard));
    if (status.accounts.length) parts.push(grid);
    setChildren(element, ...parts);
  }

  function form(): HTMLElement {
    const node = el("article", "card cloud-form");
    const picker = segmented(PROVIDERS, provider, (id) => {
      provider = id;
      formError = null;
      render();
    });
    const inputs = new Map<string, HTMLInputElement>();
    const fields = el("div", "cloud-fields");
    const name = field("Name", false, provider === "adb" ? "Phone name (optional)" : "Account name (optional)");
    for (const spec of FIELDS[provider]) {
      const f = field(spec.label, spec.secret, spec.placeholder);
      inputs.set(spec.key, f.input);
      fields.append(f.node);
    }
    fields.append(name.node);
    const install = el("input");
    install.type = "checkbox";
    install.checked = true;
    const installLabel = el("label", "cloud-check");
    installLabel.append(install, el("span", undefined, "Install Cyclone on each phone"));
    const save = actionButton(busy ? "Adding…" : "Add", { variant: "primary" });
    save.disabled = busy;
    save.addEventListener("click", () => void act(async () => {
      formError = null;
      const values = Object.fromEntries([...inputs].map(([k, input]) => [k, String(input.value ?? "").trim()]));
      if (provider === "adb") {
        // One Remote ADB account holds every address phone.
        const existing = status?.accounts.find((a) => a.provider === "adb")
          ?? await cloudApi.addAccount(client, { provider, label: "Remote ADB", installCyclone: install.checked });
        if (existing) await cloudApi.addAddress(client, existing.id, values.address, String(name.input.value ?? "").trim());
      } else {
        await cloudApi.addAccount(client, { provider, label: String(name.input.value ?? "").trim() || undefined, secrets: values,
                                            installCyclone: install.checked });
      }
      for (const input of inputs.values()) input.value = "";
      adding = false;
    }));
    node.append(picker.element, fields, installLabel);
    if (formError) node.append(el("p", "cloud-warn", formError));
    const actions = el("div", "device-actions");
    actions.append(save);
    node.append(actions, el("p", "device-problem", hint(provider)));
    return node;
  }

  function accountCard(account: CloudAccount): HTMLElement {
    const card = el("article", "card device-card cloud-account");
    card.dataset.accountId = account.id;
    const top = el("div", "device-card-top");
    const names = el("div", "device-names");
    const kept = account.phones.filter((p) => p.keep).length;
    names.append(el("span", "device-name", account.label),
      el("span", "muted", [account.providerLabel, `${account.phones.length} ${account.phones.length === 1 ? "phone" : "phones"}`, kept ? `${kept} kept connected` : ""].filter(Boolean).join(" · ")));
    top.append(names);
    card.append(top);
    if (account.error) card.append(el("p", "cloud-warn", account.error.message));
    const rows = el("ul", "cloud-phones");
    rows.append(...account.phones.map((phone) => phoneRow(account, phone)));
    if (account.phones.length) card.append(rows);
    else if (!account.error && account.provider !== "adb") card.append(el("p", "device-problem", "No phones in this account yet."));

    const actions = el("div", "device-actions");
    if (confirming.has(account.id)) {
      actions.append(el("span", "device-confirm", `Remove ${account.label}? Its phones are let go and its key is deleted from this PC.`));
      const yes = actionButton("Remove", { variant: "danger" });
      yes.addEventListener("click", () => void act(async () => {
        confirming.delete(account.id);
        await cloudApi.removeAccount(client, account.id);
      }));
      const no = actionButton("Keep", { variant: "ghost" });
      no.addEventListener("click", () => {
        confirming.delete(account.id);
        render();
      });
      actions.append(yes, no);
    } else {
      if (account.provider !== "adb") {
        const again = actionButton("Refresh", { variant: "ghost", icon: "refresh" });
        again.disabled = busy;
        again.addEventListener("click", () => void act(() => cloudApi.refresh(client, account.id)));
        actions.append(again);
      }
      const remove = actionButton("Remove", { variant: "ghost" });
      remove.addEventListener("click", () => {
        confirming.add(account.id);
        render();
      });
      actions.append(remove);
    }
    card.append(actions);
    return card;
  }

  function phoneRow(account: CloudAccount, phone: CloudPhone): HTMLElement {
    const key = `${account.id}/${phone.remoteId}`;
    const row = el("li", `cloud-phone cloud-${phone.state}`);
    row.dataset.remoteId = phone.remoteId;
    const text = el("div", "cloud-phone-text");
    const meta = [phone.android ? `Android ${phone.android.replace(/^android\s*/i, "")}` : "", phone.power === "stopped" ? "Off" : ""].filter(Boolean).join(" · ");
    text.append(el("span", "cloud-phone-name", phone.name));
    if (meta) text.append(el("span", "muted", meta));
    if (phone.keep) text.append(el("span", "cloud-phone-message", phone.message));
    const toggle = actionButton(phone.keep ? "Let go" : "Keep connected", { variant: phone.keep ? "ghost" : "primary" });
    toggle.disabled = busy;
    toggle.addEventListener("click", () => void act(() => cloudApi.keep(client, account.id, phone.remoteId, !phone.keep), key));
    const top = el("div", "cloud-phone-top");
    top.append(chip(stateLabel(phone), tone(phone)), text, toggle);
    row.append(top);
    if (account.provider === "duoplus" && phone.keep && !phone.address) row.append(addressInput(account, phone, key));
    const error = phoneErrors.get(key);
    if (error) row.append(el("p", "cloud-warn", error));
    return row;
  }

  function addressInput(account: CloudAccount, phone: CloudPhone, key: string): HTMLElement {
    const wrap = el("div", "cloud-address");
    const input = el("input", "cloud-input");
    input.placeholder = "ADB address from DuoPlus, like 203.0.113.7:5555";
    input.setAttribute("aria-label", `ADB address for ${phone.name}`);
    const save = actionButton("Save", { variant: "secondary" });
    save.addEventListener("click", () => void act(() => cloudApi.setAddress(client, account.id, phone.remoteId, String(input.value ?? "")), key));
    wrap.append(input, save);
    return wrap;
  }

  void refresh();
  return {
    element,
    refresh,
    destroy() {
      destroyed = true;
      cancel?.();
    },
  };
}

function field(label: string, secret: boolean, placeholder: string): { node: HTMLElement; input: HTMLInputElement } {
  const node = el("label", "cloud-field");
  const input = el("input", "cloud-input");
  input.type = secret ? "password" : "text";
  input.autocomplete = "off";
  input.spellcheck = false;
  input.placeholder = placeholder;
  node.append(el("span", "cloud-field-label", label), input);
  return { node, input };
}

function hint(provider: CloudProvider): string {
  if (provider === "vmos") return "Cyclone asks VMOS for 7-day ADB access to the phones you keep and renews it before it runs out. The key is encrypted for your Windows user and never shown again.";
  if (provider === "duoplus") return "In DuoPlus, turn on ADB for the phones and add this PC's internet address to the ADB whitelist. Then paste each phone's ADB address here once.";
  return "Any phone reachable at an ADB address: Cyclone keeps adb connected to it and reconnects when it drops.";
}

export function stateLabel(phone: CloudPhone): string {
  if (!phone.keep) return "Not kept";
  switch (phone.state) {
    case "connected": return "Connected";
    case "needs_you": return "Needs you";
    case "off": return "Off";
    case "waiting": return "Retrying";
    default: return "Connecting";
  }
}

function tone(phone: CloudPhone): Tone {
  if (!phone.keep) return "neutral";
  if (phone.state === "connected") return "success";
  if (phone.state === "needs_you") return "danger";
  if (phone.state === "waiting" || phone.state === "off") return "warning";
  return "accent";
}
