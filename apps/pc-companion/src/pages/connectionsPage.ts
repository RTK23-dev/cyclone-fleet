import { invoke } from "@tauri-apps/api/core";
import {
  DIRECT_LIVE_PHONE_AGENT_PROMPT,
  directLivePhoneHandoff,
  type DirectLiveBridgeStatus,
} from "../core/directLivePhone.js";
import type { LivePhoneStatus } from "../core/livePhone.js";
import {
  adapterLabel,
  adapterRowLabel,
  aiStatusLabel,
  overallAiState,
  phoneDoesNotFailAi,
  phoneHealthState,
  phoneStatusLabel,
  repairActions,
  type AiState,
  type LocalAiAdapterHealth,
  type LocalAiHealth,
  type PhoneHealth,
} from "../core/localAiHealth.js";
import { stoppedTunnelStatus, type McpTunnelMode, type McpTunnelStatus } from "../core/mcpTunnel.js";
import { CODEX_MCP_PROMPT } from "../core/sessionTiles.js";
import type { ConnectorCard, DesktopDevice, DesktopService } from "../services/types.js";
import { button, el } from "../ui/dom.js";

export interface ConnectionsPageHandle {
  element: HTMLElement;
  destroy(): void;
}

export const CODEX_PROMPT = CODEX_MCP_PROMPT;

export function createConnectionsPage(service: DesktopService): ConnectionsPageHandle {
  const page = el("section", "page content-page connections-page simple-connections-page");
  const header = el("header", "simple-connections-header");
  const heading = el("div");
  heading.append(
    el("h1", "page-title", "Connections"),
    el("p", "page-subtitle", "Choose Cloud AI or Local AI. Cyclone keeps MCP, ports and tokens out of the way."),
  );
  const refreshButton = button("Refresh", "button secondary compact");
  header.append(heading, refreshButton);

  const cards = el("div", "simple-connection-grid");

  const cloudCard = el("article", "simple-connection-card featured");
  const cloudTop = el("div", "simple-connection-top");
  const cloudIdentity = el("div", "simple-connection-identity");
  cloudIdentity.append(
    el("div", "simple-connection-icon", "AI"),
    el("div", "simple-connection-heading"),
  );
  const cloudHeading = cloudIdentity.lastElementChild as HTMLElement;
  cloudHeading.append(
    el("h2", "simple-connection-title", "Cloud AI"),
    el("p", "simple-connection-copy", "ChatGPT / Grok cloud"),
  );
  const cloudPill = el("span", "simple-status neutral", "Off");
  cloudTop.append(cloudIdentity, cloudPill);
  const cloudFacts = el("div", "simple-facts");
  const cloudPhone = fact("Phone", "Checking…");
  const cloudVision = fact("Vision", "Waiting");
  const cloudControl = fact("Control", "Waiting");
  cloudFacts.append(cloudPhone, cloudVision, cloudControl);
  const cloudMessage = el("div", "simple-connection-message", "Nothing is shared until you connect.");
  const cloudActions = el("div", "simple-choice-row");
  const cloudYes = button("Connect", "button primary");
  const cloudNo = button("Disconnect", "button secondary");
  cloudActions.append(cloudYes, cloudNo);
  const cloudHelper = el("div", "simple-helper", "Cyclone gives you one private handoff to paste into the connector setup.");
  cloudCard.append(cloudTop, cloudFacts, cloudMessage, cloudActions, cloudHelper);

  const localCard = el("article", "simple-connection-card");
  const localTop = el("div", "simple-connection-top");
  const localIdentity = el("div", "simple-connection-identity");
  localIdentity.append(
    el("div", "simple-connection-icon local-ai", "PC"),
    el("div", "simple-connection-heading"),
  );
  const localHeading = localIdentity.lastElementChild as HTMLElement;
  localHeading.append(
    el("h2", "simple-connection-title", "Local AI"),
    el("p", "simple-connection-copy", "Apps on this PC"),
  );
  const localPill = el("span", "simple-status neutral", "Checking");
  localTop.append(localIdentity, localPill);
  const localProviders = el("div", "local-ai-providers");
  const localMessage = el("div", "simple-connection-message", "Cyclone will configure the local connection for you.");
  const localActions = el("div", "simple-choice-row");
  const localConnect = button("Connect apps", "button primary");
  localActions.append(localConnect);
  localCard.append(localTop, localProviders, localMessage, localActions);

  cards.append(cloudCard, localCard);

  const health = el("section", "connection-health-split");
  const aiHealth = el("article", "health-layer");
  const aiHealthTitle = el("h3", "health-layer-title", "Local AI");
  const aiHealthState = el("div", "health-layer-state", "Checking…");
  const aiHealthAction = button("Configure", "button secondary compact");
  aiHealth.append(aiHealthTitle, aiHealthState, aiHealthAction);
  const phoneHealthCard = el("article", "health-layer");
  const phoneHealthTitle = el("h3", "health-layer-title", "Phone");
  const phoneHealthStateNode = el("div", "health-layer-state", "Checking…");
  const phoneHealthAction = button("Connect phone", "button secondary compact");
  phoneHealthCard.append(phoneHealthTitle, phoneHealthStateNode, phoneHealthAction);
  health.append(aiHealth, phoneHealthCard);

  const advanced = el("details", "simple-advanced") as HTMLDetailsElement;
  const advancedSummary = el("summary", "simple-advanced-summary");
  advancedSummary.append(
    el("span", "simple-advanced-title", "Advanced"),
    el("span", "simple-advanced-copy", "URLs, tokens, MCP details and diagnostics"),
  );
  const advancedBody = el("div", "simple-advanced-body");

  const remote = el("article", "advanced-connection-block");
  const remoteHead = el("div", "advanced-block-head");
  const remoteTitle = el("div");
  remoteTitle.append(el("h3", "advanced-block-title", "Legacy Remote MCP"), el("p", "advanced-block-copy", "Keep this only for cloud clients that need the older general Cyclone MCP surface."));
  const remotePill = el("span", "simple-status neutral", "Off");
  remoteHead.append(remoteTitle, remotePill);
  const remoteInfo = el("div", "advanced-inline-info", "Off");
  const remoteActions = el("div", "advanced-action-row");
  const remoteStart = button("Start", "button secondary compact");
  const remoteView = button("View only", "button ghost compact");
  const remoteControl = button("Control phone", "button ghost compact");
  const remoteCopyUrl = button("Copy URL", "button ghost compact");
  const remoteCopyToken = button("Copy token", "button ghost compact");
  remoteActions.append(remoteStart, remoteView, remoteControl, remoteCopyUrl, remoteCopyToken);
  remote.append(remoteHead, remoteInfo, remoteActions);

  const localAdvanced = el("article", "advanced-connection-block");
  const localHead = el("div", "advanced-block-head");
  localHead.append(
    el("div", "advanced-block-heading-wrap"),
    el("span", "simple-status neutral", "Optional"),
  );
  const localTitle = localHead.firstElementChild as HTMLElement;
  localTitle.append(el("h3", "advanced-block-title", "Other local MCP clients"), el("p", "advanced-block-copy", "DeepSeek harnesses and generic local MCP clients can use Cyclone's typed local transport."));
  const localList = el("div", "advanced-local-list");
  localAdvanced.append(localHead, localList);

  const promptBlock = el("article", "advanced-connection-block prompt-block");
  const promptHead = el("div", "advanced-block-head");
  promptHead.append(el("div", "advanced-block-title", "Agent prompts"));
  const promptActions = el("div", "advanced-action-row");
  const copyLivePrompt = button("Copy Live Phone prompt", "button ghost compact");
  const copyCodexPrompt = button("Copy Codex prompt", "button ghost compact");
  promptActions.append(copyLivePrompt, copyCodexPrompt);
  promptBlock.append(promptHead, promptActions);

  advancedBody.append(remote, localAdvanced, promptBlock);
  advanced.append(advancedSummary, advancedBody);
  page.append(header, cards, health, advanced);

  let active = true;
  let busy = false;
  let directStatus: DirectLiveBridgeStatus = { running: false, url: null };
  let liveStatus: LivePhoneStatus = { connected: false, vision: false, control: false, enabled: false, stopped: false };
  let devices: DesktopDevice[] = [];
  let connectors: ConnectorCard[] = [];
  let remoteStatus: McpTunnelStatus = stoppedTunnelStatus();

  const localHealth = (): LocalAiHealth => {
    const adapters: LocalAiAdapterHealth[] = connectors
      .filter((item) => item.id !== "deepseek-mcp")
      .map((item) => ({
        id: item.id,
        name: item.name || adapterLabel(item.id),
        state: (item.aiState ?? (item.state === "CONNECTED" ? "CONNECTED" : item.state === "NOT_INSTALLED" ? "UNKNOWN" : item.state === "NEEDS_ATTENTION" ? "FAILED" : "DETECTED")) as AiState,
        detected: Boolean(item.detected ?? item.state !== "NOT_INSTALLED"),
        configured: Boolean(item.configured ?? item.state === "CONNECTED"),
        detail: item.description,
      }));
    return { state: overallAiState(adapters.map((item) => item.state)), adapters };
  };

  const phoneHealth = (): PhoneHealth => {
    const physical = devices.filter((device) => device.source !== "VIRTUAL");
    const ready = physical.filter((device) => device.state === "READY").length;
    const fromConnectors = connectors[0];
    return {
      state: phoneHealthState({
        reachable: fromConnectors?.gatewayReachable ?? physical.length > 0,
        deviceCount: fromConnectors?.deviceCount ?? physical.length,
        readyDeviceCount: fromConnectors?.readyDeviceCount ?? ready,
      }),
      reachable: fromConnectors?.gatewayReachable ?? physical.length > 0,
      readyDeviceCount: fromConnectors?.readyDeviceCount ?? ready,
    };
  };

  const render = (): void => {
    if (!active) return;
    const physical = devices.filter((device) => device.source !== "VIRTUAL");
    const readyPhone = physical.find((device) => device.state === "READY");
    const cloudReady = directStatus.running && Boolean(directStatus.url);
    setStatus(cloudPill, cloudReady ? "Ready" : directStatus.running ? "Starting" : "Off", cloudReady ? "ready" : directStatus.running ? "busy" : "neutral");
    setFactValue(cloudPhone, readyPhone ? readyPhone.name : physical.length ? "Connected" : "Not connected");
    setFactValue(cloudVision, liveStatus.vision ? "Ready" : "Waiting");
    setFactValue(cloudControl, liveStatus.control ? "Ready" : liveStatus.enabled ? "Waiting" : "Off");
    cloudYes.disabled = busy;
    cloudYes.textContent = cloudReady ? "Copy handoff" : "Connect";
    cloudNo.disabled = busy || !(cloudReady || directStatus.running);
    cloudMessage.textContent = cloudReady
      ? readyPhone
        ? "Connected. Use the handoff once in your cloud AI connector, then start asking it to use your phone."
        : "Cloud AI is ready. Connect a phone in Control before asking the agent to act."
      : "Nothing is shared until you connect.";

    const ai = localHealth();
    const phone = phoneHealth();
    const engineReady = connectors.some((item) => (item.toolCount ?? 0) > 0) || ai.state === "CONNECTED" || ai.state === "CONFIGURED";
    void phoneDoesNotFailAi(ai, phone);
    setStatus(
      localPill,
      aiStatusLabel(ai.state),
      ai.state === "CONNECTED" || ai.state === "CONFIGURED" ? "ready" : ai.state === "FAILED" ? "attention" : "neutral",
    );
    localProviders.replaceChildren();
    for (const adapter of ai.adapters.filter((item) => item.id !== "generic")) {
      localProviders.append(el("div", "local-ai-provider", adapterRowLabel(adapter)));
    }
    const pending = ai.adapters.filter((item) => item.detected && item.state !== "CONNECTED" && item.state !== "CONFIGURED" && item.id !== "generic");
    localConnect.disabled = busy || pending.length === 0;
    localConnect.textContent = pending.length ? "Connect apps" : ai.state === "CONNECTED" || ai.state === "CONFIGURED" ? "Connected" : "Connect apps";
    localMessage.textContent = ai.state === "CONNECTED" || ai.state === "CONFIGURED"
      ? "Local AI is configured. Phone readiness is tracked separately below."
      : "Cyclone will configure supported local AI apps for you.";

    aiHealthState.textContent = `${aiStatusLabel(ai.state)}${ai.state === "CONNECTED" || ai.state === "CONFIGURED" ? " ✓" : ""}`;
    phoneHealthStateNode.textContent = `${phoneStatusLabel(phone.state)}${phone.state === "READY" ? " ✓" : phone.state === "DISCONNECTED" ? " ✕" : ""}`;
    const repairs = repairActions({ ai, phone, engineReady });
    const aiRepair = repairs.find((item) => item.layer === "local_ai");
    const phoneRepair = repairs.find((item) => item.layer === "phone");
    aiHealthAction.hidden = !aiRepair;
    aiHealthAction.textContent = aiRepair?.label ?? "Configure";
    phoneHealthAction.hidden = !phoneRepair;
    phoneHealthAction.textContent = phoneRepair?.label ?? "Connect phone";

    setStatus(remotePill, remoteStatus.state === "running" ? "Running" : remoteStatus.state === "degraded" ? "Needs repair" : "Off", remoteStatus.state === "running" ? "ready" : remoteStatus.state === "degraded" ? "attention" : "neutral");
    remoteInfo.textContent = remoteStatus.state === "running"
      ? `${remoteStatus.mode === "full" ? "Phone control" : "View only"} · public bridge ready`
      : remoteStatus.error || remoteStatus.message || "Off";
    remoteStart.textContent = remoteStatus.state === "running" ? "Stop" : remoteStatus.state === "degraded" ? "Repair" : "Start";
    remoteView.classList.toggle("selected", remoteStatus.mode === "readonly");
    remoteControl.classList.toggle("selected", remoteStatus.mode === "full");
    remoteCopyUrl.disabled = !remoteStatus.mcpUrl;
    remoteCopyToken.disabled = remoteStatus.state !== "running";

    renderLocalConnectors();
  };

  const refresh = async (): Promise<void> => {
    if (busy) return;
    const results = await Promise.allSettled([
      service.listDevices(),
      service.listConnectors(),
      service.getMcpTunnelStatus(),
      invoke<DirectLiveBridgeStatus>("live_bridge_status"),
      invoke<LivePhoneStatus>("live_phone_status"),
    ]);
    if (!active) return;
    if (results[0].status === "fulfilled") devices = results[0].value;
    if (results[1].status === "fulfilled") connectors = results[1].value;
    if (results[2].status === "fulfilled") remoteStatus = results[2].value;
    if (results[3].status === "fulfilled") directStatus = results[3].value;
    if (results[4].status === "fulfilled") liveStatus = results[4].value;
    render();
  };

  const copyCloudHandoff = async (): Promise<void> => {
    if (!directStatus.url) throw new Error("Direct Live Phone URL is not ready yet.");
    const secret = await invoke<{ token: string }>("live_bridge_token");
    await navigator.clipboard.writeText(directLivePhoneHandoff(directStatus.url, secret.token));
    cloudMessage.textContent = "Private handoff copied. Paste it only into your connector setup/import flow — not into an ordinary chat message.";
    flashButton(cloudYes, "Handoff copied");
  };

  cloudYes.addEventListener("click", async () => {
    if (busy) return;
    busy = true;
    try {
      if (!(directStatus.running && directStatus.url)) {
        cloudMessage.textContent = "Connecting securely…";
        await invoke("live_phone_control", { action: "enable" });
        directStatus = await invoke<DirectLiveBridgeStatus>("live_bridge_connect");
      }
      render();
      await copyCloudHandoff();
    } catch (error) {
      cloudMessage.textContent = friendlyError(error, "Cyclone could not start the cloud connection.");
    } finally {
      busy = false;
      await refresh();
    }
  });

  cloudNo.addEventListener("click", async () => {
    if (busy) return;
    if (!(directStatus.running || directStatus.url)) {
      cloudMessage.textContent = "Nothing changed. Cloud agents remain disconnected.";
      return;
    }
    busy = true;
    try {
      await invoke("live_bridge_disconnect");
      cloudMessage.textContent = "Cloud agents disconnected.";
    } catch (error) {
      cloudMessage.textContent = friendlyError(error, "Could not disconnect the cloud bridge.");
    } finally {
      busy = false;
      await refresh();
    }
  });

  localConnect.addEventListener("click", async () => {
    if (busy) return;
    const pending = localHealth().adapters.filter((item) => item.detected && item.state !== "CONNECTED" && item.state !== "CONFIGURED" && item.id !== "generic");
    if (!pending.length) {
      localMessage.textContent = "No local AI apps need configuration.";
      return;
    }
    busy = true;
    try {
      for (const adapter of pending) {
        const result = await service.runConnectorAction(adapter.id, adapter.state === "FAILED" ? "repair" : "connect");
        localMessage.textContent = result.message || `${adapter.name} updated.`;
      }
    } catch (error) {
      localMessage.textContent = friendlyError(error, "Cyclone could not update the Local AI connection.");
    } finally {
      busy = false;
      await refresh();
    }
  });

  aiHealthAction.addEventListener("click", () => localConnect.click());
  phoneHealthAction.addEventListener("click", () => {
    phoneHealthStateNode.textContent = "Connect a phone from Control.";
  });

  remoteStart.addEventListener("click", async () => {
    if (busy) return;
    busy = true;
    try {
      remoteStatus = remoteStatus.state === "running"
        ? await service.stopMcpTunnel()
        : remoteStatus.state === "degraded"
          ? await service.restartMcpTunnel()
          : await service.startMcpTunnel(remoteStatus.mode);
    } catch (error) {
      remoteInfo.textContent = friendlyError(error, "Remote MCP command failed.");
    } finally {
      busy = false;
      await refresh();
    }
  });

  for (const [control, mode] of [[remoteView, "readonly"], [remoteControl, "full"]] as Array<[HTMLButtonElement, McpTunnelMode]>) {
    control.addEventListener("click", async () => {
      if (busy || mode === remoteStatus.mode) return;
      if (mode === "full" && !window.confirm("Control phone allows this legacy Remote MCP client to perform phone actions. Enable it?")) return;
      busy = true;
      try { remoteStatus = await service.setMcpTunnelMode(mode); }
      finally { busy = false; await refresh(); }
    });
  }

  remoteCopyUrl.addEventListener("click", () => {
    if (remoteStatus.mcpUrl) void copyText(remoteStatus.mcpUrl, remoteCopyUrl, "URL copied");
  });
  remoteCopyToken.addEventListener("click", async () => {
    try {
      const secret = await service.copyMcpTunnelToken();
      await copyText(secret.token, remoteCopyToken, "Token copied");
    } catch (error) {
      remoteInfo.textContent = friendlyError(error, "Could not copy token.");
    }
  });
  copyLivePrompt.addEventListener("click", () => void copyText(DIRECT_LIVE_PHONE_AGENT_PROMPT, copyLivePrompt, "Prompt copied"));
  copyCodexPrompt.addEventListener("click", () => void copyText(CODEX_PROMPT, copyCodexPrompt, "Prompt copied"));
  refreshButton.addEventListener("click", () => void refresh());

  const renderLocalConnectors = (): void => {
    const others = connectors.filter((item) => item.id !== "codex" && item.id !== "grok");
    localList.replaceChildren();
    if (!others.length) {
      localList.append(el("div", "advanced-empty", "No other local MCP clients detected."));
      return;
    }
    for (const connector of others) {
      const row = el("div", "advanced-local-row");
      const copy = el("div");
      copy.append(
        el("div", "advanced-local-name", connector.name),
        el("div", "advanced-local-state", connector.state === "CONNECTED" ? "Connected" : connector.state === "NEEDS_ATTENTION" ? "Configure" : connector.state === "NOT_INSTALLED" ? "Not installed" : "Available"),
      );
      const action = button(connector.state === "CONNECTED" ? "Connected" : connector.state === "NEEDS_ATTENTION" ? "Configure" : "Prepare", "button ghost compact");
      action.disabled = connector.state === "CONNECTED";
      action.addEventListener("click", async () => {
        const kind = connector.state === "NEEDS_ATTENTION" ? "repair" : connector.state === "NOT_INSTALLED" ? "install" : "connect";
        try { await service.runConnectorAction(connector.id, kind); }
        finally { await refresh(); }
      });
      row.append(copy, action);
      localList.append(row);
    }
  };

  void refresh();
  const timer = window.setInterval(() => { void refresh(); }, 5_000);
  return {
    element: page,
    destroy: () => {
      active = false;
      window.clearInterval(timer);
    },
  };
}

function fact(label: string, value: string): HTMLElement {
  const item = el("div", "simple-fact");
  item.append(el("div", "simple-fact-label", label), el("div", "simple-fact-value", value));
  return item;
}

function setFactValue(item: HTMLElement, value: string): void {
  const node = item.querySelector<HTMLElement>(".simple-fact-value");
  if (node) node.textContent = value;
}

function setStatus(node: HTMLElement, text: string, tone: "ready" | "attention" | "busy" | "neutral"): void {
  node.textContent = text;
  node.className = `simple-status ${tone}`;
}

async function copyText(text: string, control: HTMLButtonElement, success: string): Promise<void> {
  await navigator.clipboard.writeText(text);
  flashButton(control, success);
}

function flashButton(control: HTMLButtonElement, label: string): void {
  const original = control.textContent || "Copy";
  control.textContent = label;
  window.setTimeout(() => { control.textContent = original; }, 1_500);
}

function friendlyError(error: unknown, fallback: string): string {
  const text = error instanceof Error ? error.message.trim() : "";
  return text || fallback;
}
