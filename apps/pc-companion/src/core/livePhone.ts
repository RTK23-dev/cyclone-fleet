import type { DesktopDevice } from "../services/types.js";

export interface LivePhoneStatus {
  connected: boolean;
  vision: boolean;
  control: boolean;
  enabled: boolean;
  stopped: boolean;
  live_phone_broker_ready?: boolean;
  gateway_ready?: boolean;
  accessibility_ready?: boolean;
  cloud_connector_ready?: boolean;
}

export function livePhoneLabels(state: LivePhoneStatus, devices: DesktopDevice[]) {
  const physical = devices.filter((d) => d.source === "USB" || d.source === "LAN");
  return {
    phone: physical.length
      ? physical.map((d) => `${d.name} · ${d.connectionLabel}`).join(" / ")
      : "Connect and pair a physical Android phone",
    cloud: state.cloud_connector_ready
      ? "Direct cloud connector · Recent MCP request"
      : state.connected
        ? "Fallback PC connector · Recent request"
        : "Cloud connector · Waiting for a request",
    broker: state.live_phone_broker_ready ? "Live Phone broker · Ready" : "Live Phone broker · Waiting",
    gateway: state.gateway_ready ? "Gateway · Ready" : "Gateway · Waiting for a verified response",
    accessibility: state.accessibility_ready ? "Accessibility · Ready" : "Accessibility · Not yet verified",
    vision: state.vision ? "Vision · Ready" : "Vision · Observe the phone first",
    control: state.stopped
      ? "Control · Stopped"
      : !state.enabled
        ? "Control · Paused"
        : state.control
          ? "Control · Ready"
          : "Control · Enabled; waiting for a verified phone response",
  };
}
