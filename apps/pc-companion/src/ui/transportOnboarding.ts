import type { DesktopService } from "../services/types.js";
import { TransportOnboardingClient, type TransportStatus } from "../services/transportOnboardingClient.js";
import { button, el } from "./dom.js";

/**
 * Always-available first-connect surface for the real Cyclone One runtime.
 *
 * This is transport onboarding only. A successful ADB connection makes the phone discoverable;
 * Cyclone trust pairing remains a separate step in the normal Fleet/phone card flow.
 */
export function mountTransportOnboarding(service: DesktopService): () => void {
  if (service.mode !== "real") return () => undefined;

  const client = new TransportOnboardingClient();
  const launcher = button("Connect phone", "button primary compact transport-connect-launcher");
  launcher.type = "button";
  launcher.setAttribute("aria-label", "Connect an Android phone by USB, Wireless debugging, or VMOS ADB");
  Object.assign(launcher.style, {
    position: "fixed",
    right: "22px",
    bottom: "22px",
    zIndex: "1200",
    boxShadow: "0 12px 32px rgba(0,0,0,.35)",
  });

  const dialog = document.createElement("dialog");
  dialog.className = "transport-onboarding-dialog";
  dialog.setAttribute("aria-label", "Connect Android phone");
  Object.assign(dialog.style, {
    width: "min(620px, calc(100vw - 32px))",
    maxHeight: "min(760px, calc(100vh - 32px))",
    overflow: "auto",
    border: "1px solid rgba(255,255,255,.12)",
    borderRadius: "18px",
    padding: "0",
    background: "#111217",
    color: "#f7f7f8",
    boxShadow: "0 28px 90px rgba(0,0,0,.62)",
  });

  const panel = el("section", "transport-onboarding-panel");
  Object.assign(panel.style, { padding: "22px", display: "grid", gap: "18px" });
  const heading = el("div");
  heading.append(
    el("h2", "page-title", "Connect a phone"),
    el("p", "page-subtitle", "Choose USB, Wireless, or VMOS. Cyclone One already includes Android Platform-Tools 37.0.1, so no Android Studio or separate ADB install is needed."),
    el("p", "page-subtitle", "ADB connection comes first. When it is ready, open Cyclone Mobile → PC Gateway & QR pairing to finish Cyclone trust pairing."),
  );
  const close = button("Close", "button ghost compact");
  close.type = "button";
  close.addEventListener("click", () => dialog.close());
  const top = el("div");
  Object.assign(top.style, { display: "flex", justifyContent: "space-between", gap: "16px", alignItems: "start" });
  top.append(heading, close);

  const tabs = el("div");
  Object.assign(tabs.style, { display: "grid", gridTemplateColumns: "repeat(3, 1fr)", gap: "8px" });
  const body = el("div");
  const status = el("div", "transport-onboarding-status");
  status.setAttribute("role", "status");
  Object.assign(status.style, { minHeight: "42px", color: "#c9cad1", lineHeight: "1.45" });
  panel.append(top, tabs, body, status);
  dialog.append(panel);

  type Mode = "usb" | "wifi" | "vmos";
  let mode: Mode = "usb";
  let busy = false;

  const setStatus = (message: string, ok?: boolean) => {
    status.textContent = message;
    status.dataset.state = ok === true ? "ready" : ok === false ? "attention" : "idle";
  };

  const finish = async (result: TransportStatus) => {
    const message = result.ok
      ? `${result.next || "ADB transport is ready."} Next: open Cyclone Mobile → PC Gateway & QR pairing to finish trust pairing.`
      : result.next || "Transport needs attention.";
    setStatus(message, result.ok);
    if (result.ok) {
      // The authenticated transport route already refreshes DeviceFleetManager. This extra normal
      // UI scan makes the operator-visible Fleet deterministic even if its websocket refresh races.
      try { await service.scanDevices(); } catch { /* Fleet event stream remains the recovery path. */ }
    }
  };

  const run = async (action: () => Promise<TransportStatus>, control: HTMLButtonElement) => {
    if (busy) return;
    busy = true;
    control.disabled = true;
    const original = control.textContent;
    control.textContent = "Connecting…";
    setStatus("Checking ADB transport…");
    try {
      await finish(await action());
    } catch (error) {
      setStatus(error instanceof Error ? error.message : "Cyclone could not complete that connection step.", false);
    } finally {
      busy = false;
      control.disabled = false;
      control.textContent = original;
    }
  };

  const field = (label: string, placeholder: string, type = "text") => {
    const wrapper = el("label");
    Object.assign(wrapper.style, { display: "grid", gap: "6px", fontSize: "13px", color: "#c9cad1" });
    wrapper.append(el("span", "", label));
    const input = el("input") as HTMLInputElement;
    input.type = type;
    input.placeholder = placeholder;
    input.autocomplete = "off";
    input.spellcheck = false;
    Object.assign(input.style, {
      width: "100%",
      boxSizing: "border-box",
      borderRadius: "10px",
      border: "1px solid rgba(255,255,255,.13)",
      padding: "11px 12px",
      background: "#0b0c10",
      color: "#f7f7f8",
    });
    wrapper.append(input);
    return { wrapper, input };
  };

  const renderMode = () => {
    body.replaceChildren();
    setStatus("");
    for (const node of Array.from(tabs.querySelectorAll("button"))) {
      node.classList.toggle("primary", (node as HTMLButtonElement).dataset.mode === mode);
    }

    if (mode === "usb") {
      const section = el("div");
      Object.assign(section.style, { display: "grid", gap: "14px" });
      section.append(
        el("p", "page-subtitle", "1. Connect USB. 2. On Android enable Developer options → USB debugging. 3. Unlock the phone and approve this PC. 4. Click Check USB."),
      );
      const check = button("Check USB", "button primary");
      check.addEventListener("click", () => void run(() => client.usbStatus(), check));
      section.append(check);
      body.append(section);
      return;
    }

    if (mode === "wifi") {
      const section = el("div");
      Object.assign(section.style, { display: "grid", gap: "12px" });
      section.append(el("p", "page-subtitle", "Android Settings → Developer options → Wireless debugging → Pair device with pairing code. The pairing address and the normal device address use different ports."));
      const pair = field("Pairing address", "192.168.1.25:37123");
      const connect = field("Device address", "192.168.1.25:42891");
      const code = field("6-digit pairing code", "123456", "password");
      code.input.inputMode = "numeric";
      code.input.maxLength = 6;
      const pairButton = button("Pair & connect", "button primary");
      pairButton.addEventListener("click", () => {
        const secret = code.input.value;
        code.input.value = ""; // do not retain the Android pairing secret in the DOM after submit
        void run(() => client.pairWireless(pair.input.value, connect.input.value, secret), pairButton);
      });
      section.append(pair.wrapper, connect.wrapper, code.wrapper, pairButton);
      body.append(section);
      return;
    }

    const section = el("div");
    Object.assign(section.style, { display: "grid", gap: "12px" });
    section.append(
      el("p", "page-subtitle", "Recommended VMOS image: Android 15. Android 13/14 are compatible; Android 12 and older are unsupported for this Cyclone setup."),
      el("p", "page-subtitle", "Install Cyclone Mobile 4.3.6 inside VMOS. In VMOS/provider settings enable its ADB/Local Debugging tunnel, copy the provider ADB endpoint, then connect it here."),
    );
    const endpoint = field("VMOS / remote ADB endpoint", "host.example:5555");
    const connectButton = button("Connect VMOS phone", "button primary");
    connectButton.addEventListener("click", () => void run(() => client.connectVmos(endpoint.input.value), connectButton));
    section.append(endpoint.wrapper, connectButton);
    body.append(section);
  };

  for (const [value, label] of [["usb", "USB"], ["wifi", "Wireless"], ["vmos", "VMOS"]] as const) {
    const tab = button(label, "button secondary compact");
    tab.type = "button";
    tab.dataset.mode = value;
    tab.addEventListener("click", () => { mode = value; renderMode(); });
    tabs.append(tab);
  }

  launcher.addEventListener("click", () => {
    mode = "usb";
    renderMode();
    dialog.showModal();
  });
  dialog.addEventListener("click", (event) => {
    if (event.target === dialog) dialog.close();
  });

  document.body.append(dialog, launcher);
  renderMode();
  return () => {
    dialog.close();
    dialog.remove();
    launcher.remove();
  };
}
