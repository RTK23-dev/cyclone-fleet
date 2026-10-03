/** Honest states shared by every phone-backed page: no gateway, no phone, not paired, old Cyclone. */
import type { GlassContext } from "../app.js";
import { deviceReadiness } from "../services/devices.js";
import { actionButton, emptyState } from "../ui/components.js";

export function deviceGate(ctx: GlassContext): HTMLElement | null {
  if (ctx.devicesError) {
    if (ctx.devicesError.sessionExpired) {
      return emptyState({
        icon: "plug",
        tone: "warning",
        title: "This Glass session has ended",
        body: "Cyclone One restarted or the session expired. Open Glass again from Cyclone One → Settings → Open Cyclone Glass.",
      });
    }
    const retry = actionButton("Try again", { icon: "refresh" });
    retry.addEventListener("click", () => void ctx.refreshDevices());
    return emptyState({
      icon: "plug",
      tone: "danger",
      title: "Cyclone's gateway is not answering",
      body: "Glass talks to the phone through the local gateway on this PC. Start it with Cyclone One or `cyclone-device-gateway serve`.",
      action: retry,
    });
  }
  const device = ctx.device;
  if (!device) {
    const retry = actionButton("Open Devices", { icon: "plug", variant: "primary" });
    retry.addEventListener("click", () => ctx.navigate({ name: "devices" }));
    return emptyState({
      icon: "phone",
      title: "No phone connected",
      body: "Connect an Android phone with Cyclone over USB or wireless debugging.",
      action: retry,
    });
  }
  const readiness = deviceReadiness(device);
  if (readiness.ready) return null;
  const toDevices = actionButton(readiness.reason === "unpaired" ? "Connect this phone" : "Open Devices", { icon: "plug", variant: "primary" });
  toDevices.addEventListener("click", () => ctx.navigate({ name: "devices" }));
  return emptyState({
    icon: "alert",
    tone: readiness.reason === "disconnected" ? "danger" : "warning",
    title:
      readiness.reason === "needs-update"
        ? "Update Cyclone on the phone"
        : readiness.reason === "unpaired"
          ? `${device.name} is not connected to Glass yet`
          : `${device.name} is not ready`,
    body: readiness.message,
    action: readiness.reason === "needs-update" ? undefined : toDevices,
  });
}
