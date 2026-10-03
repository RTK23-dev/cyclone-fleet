export type AiState = "UNKNOWN" | "DETECTED" | "CONFIGURED" | "CONNECTED" | "FAILED";
export type PhoneHealthState = "UNKNOWN" | "CONNECTED" | "READY" | "DISCONNECTED";
export type RepairLayer = "local_ai" | "phone" | "engine";

export interface LocalAiAdapterHealth {
  id: string;
  name: string;
  state: AiState;
  detected: boolean;
  configured: boolean;
  detail: string;
}

export interface LocalAiHealth {
  state: AiState;
  adapters: LocalAiAdapterHealth[];
}

export interface PhoneHealth {
  state: PhoneHealthState;
  reachable: boolean;
  readyDeviceCount: number;
}

export interface RepairAction {
  id: string;
  layer: RepairLayer;
  action: string;
  label: string;
  detail: string;
}

export const LOCAL_AI_PROVIDERS: Array<{ id: string; name: string }> = [
  { id: "grok", name: "Grok" },
  { id: "codex", name: "Codex" },
  { id: "cursor", name: "Cursor" },
  { id: "opencode", name: "OpenCode" },
  { id: "copilot", name: "Copilot" },
  { id: "generic", name: "Generic MCP" },
];

export function adapterLabel(id: string): string {
  return LOCAL_AI_PROVIDERS.find((item) => item.id === id)?.name
    ?? id.replace(/-/g, " ").replace(/\b\w/g, (char) => char.toUpperCase());
}

export function overallAiState(states: AiState[]): AiState {
  if (states.includes("CONNECTED")) return "CONNECTED";
  if (states.includes("CONFIGURED")) return "CONFIGURED";
  if (states.includes("FAILED")) return "FAILED";
  if (states.includes("DETECTED")) return "DETECTED";
  return "UNKNOWN";
}

export function phoneHealthState(input: { reachable?: boolean; deviceCount?: number; readyDeviceCount?: number }): PhoneHealthState {
  if (!input.reachable) return "DISCONNECTED";
  if ((input.readyDeviceCount ?? 0) > 0) return "READY";
  if ((input.deviceCount ?? 0) > 0) return "CONNECTED";
  return "DISCONNECTED";
}

export function aiStatusLabel(state: AiState): string {
  if (state === "CONNECTED") return "Connected";
  if (state === "CONFIGURED") return "Configured";
  if (state === "DETECTED") return "Available";
  if (state === "FAILED") return "Configuration missing";
  return "Not installed";
}

export function phoneStatusLabel(state: PhoneHealthState): string {
  if (state === "READY") return "Ready";
  if (state === "CONNECTED") return "Connected";
  if (state === "DISCONNECTED") return "Not connected";
  return "Unknown";
}

export function adapterRowLabel(adapter: LocalAiAdapterHealth): string {
  if (adapter.state === "CONNECTED" || adapter.state === "CONFIGURED") return `${adapter.name} ✓`;
  if (adapter.state === "DETECTED") return `${adapter.name} available`;
  if (adapter.state === "FAILED") return `${adapter.name} needs configuration`;
  return `${adapter.name} not installed`;
}

export function repairActions(health: { ai: LocalAiHealth; phone: PhoneHealth; engineReady: boolean }): RepairAction[] {
  const actions: RepairAction[] = [];
  if (!health.engineReady) {
    actions.push({ id: "engine", layer: "engine", action: "restart", label: "Restart", detail: "Stopped" });
  }
  if (health.ai.state === "UNKNOWN" || health.ai.state === "DETECTED" || health.ai.state === "FAILED") {
    const configured = health.ai.adapters.some((item) => item.configured);
    actions.push({
      id: "local_ai",
      layer: "local_ai",
      action: configured ? "repair" : "configure",
      label: configured ? "Repair" : "Configure",
      detail: configured ? "Local AI needs repair" : "Configuration missing",
    });
  }
  if (health.phone.state === "DISCONNECTED" || health.phone.state === "UNKNOWN") {
    actions.push({ id: "phone", layer: "phone", action: "connect", label: "Connect phone", detail: "Not connected" });
  }
  return actions;
}

export function phoneDoesNotFailAi(ai: LocalAiHealth, phone: PhoneHealth): boolean {
  return ai.state === "CONNECTED" || ai.state === "CONFIGURED"
    ? phone.state === "DISCONNECTED" || phone.state === "UNKNOWN" || phone.state === "CONNECTED" || phone.state === "READY"
    : true;
}
