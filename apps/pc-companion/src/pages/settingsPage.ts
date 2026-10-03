import { invoke } from "@tauri-apps/api/core";
import {
  formatSmokeLog,
  friendlyTunnelState,
  MCP_TUNNEL_CONNECTOR_CHECKLIST,
  MCP_TUNNEL_FULL_WARNING,
  MCP_TUNNEL_STDIO_NOTE,
  MCP_TUNNEL_SUBTITLE,
  MCP_TUNNEL_TITLE,
  stoppedTunnelStatus,
  type McpTunnelMode,
  type McpTunnelStatus,
} from "../core/mcpTunnel.js";
import { DEFAULT_FOREGROUND_SESSION_ID, SETTINGS_MCP_SESSION_COPY } from "../core/sessionTiles.js";
import type { DesktopDevice, DesktopRuntimeStatus, DesktopService } from "../services/types.js";
import { openCycloneGlass } from "../services/glassLauncher.js";
import { button, el } from "../ui/dom.js";

export interface SettingsPageHandle {
  element: HTMLElement;
  destroy(): void;
}

export function createSettingsPage(service: DesktopService, devices: DesktopDevice[]): SettingsPageHandle {
  const page = el("section", "page content-page");
  const header = el("header", "page-header");
  header.append(
    el("h1", "page-title", "Settings & diagnostics"),
    el("p", "page-subtitle", "USB health, live Android crash monitoring, Cyclone desktop status, and the ChatGPT / Grok chat MCP tunnel."),
  );
  const cards = el("div", "settings-grid");
  const remoteMcp = createRemoteMcpCard(service);

  const companion = statusCard(
    "PC Companion",
    service.mode === "mock" ? "Development mode" : "Starting…",
    service.mode === "mock" ? "Using mock phones for UI development." : "Checking the local Cyclone Gateway.",
  );
  const installPath = statusCard(
    "Install path",
    "Cyclone One",
    "Installs to %LOCALAPPDATA%\\Cyclone One. Uninstall Cyclone PC Companion 3.8.x if it remains beside One; doctor reports this.",
  );
  const glassAtlas = statusCard(
    "Cyclone Glass",
    "Opens in your browser",
    "Glass is Cyclone's developer dashboard: every app and its map, the phone live, and Ask. It runs on this PC only and needs Mobile 5.0 on the phone. One's own Ask/Maps pages declare Foreground (default-foreground) or the named Session Kernel VD; named ids are never rewritten to display 0.",
  );
  const openGlass = button("Open Cyclone Glass", "button primary wide");
  openGlass.addEventListener("click", () => {
    openGlass.disabled = true;
    void openCycloneGlass(service.glassGateway, (path) => invoke<string>("open_glass", { path }))
      .then((address) => setCard(glassAtlas, "Opened in your browser", `Glass is at ${address} on this PC.`))
      .catch((error: unknown) => setCard(glassAtlas, "Could not open", error instanceof Error ? error.message : String(error)))
      .finally(() => {
        openGlass.disabled = false;
      });
  });
  glassAtlas.append(openGlass);
  const phones = statusCard(
    "Phones",
    `${devices.length} detected`,
    devices.length === 0 ? "No phones are currently in Cyclone's fleet." : "Phone screens and controls stay isolated per device.",
  );
  const mcpLiveDisplay = statusCard(
    "MCP live display",
    `session_id=${DEFAULT_FOREGROUND_SESSION_ID}`,
    SETTINGS_MCP_SESSION_COPY,
  );
  const adb = statusCard("ADB connection", "Checking…", "Cyclone is checking Android Platform Tools and USB devices.");
  const adbPath = el("div", "diagnostic-path", "");
  adb.append(adbPath);
  const autoDetect = statusCard("Auto-detect", "Checking…", "Cyclone listens for USB device changes and keeps a low-rate fallback scan.");
  const bridgeRecovery = statusCard("Bridge recovery", "Checking…", "Paired phones that lose the USB bridge reconnect with bounded backoff instead of hanging.");
  const crashDiagnostics = statusCard(
    "Live USB crash monitor",
    "Checking…",
    "Starts automatically when an authorized Android phone is detected, before you press Pair.",
  );
  const connectionDiagnostics = statusCard(
    "Connection debug flow",
    "Ready to capture",
    "Creates one sendable zip with the PC WebSocket timeline, bounded Android process evidence, authenticated bridge health, and a content-free screen-capture probe.",
  );
  const runConnectionDiagnostics = button("Create connection debug bundle", "button secondary wide");
  const connectionDiagnosticsPath = el("div", "diagnostic-path", "No bundle created in this session.");
  runConnectionDiagnostics.disabled = !devices.some((device) => device.paired);
  runConnectionDiagnostics.addEventListener("click", async () => {
    const device = devices.find((candidate) => candidate.paired);
    if (!device) return;
    runConnectionDiagnostics.disabled = true;
    setCard(connectionDiagnostics, "Collecting…", "Cyclone is running fixed read-only checks. Screen pixels, credentials, pairing codes, clipboard and typed content are not saved.");
    try {
      const bundle = await service.createConnectionDiagnosticBundle(device.id);
      connectionDiagnosticsPath.textContent = bundle.path;
      setCard(connectionDiagnostics, "Bundle saved", "Send this zip with a bug report. It contains the exact last client/server stream stages and bounded phone health evidence.");
    } catch {
      setCard(connectionDiagnostics, "Capture failed", "The local Gateway could not complete the debug flow. Reopen Cyclone and retry.");
    } finally {
      runConnectionDiagnostics.disabled = false;
    }
  });
  connectionDiagnostics.append(connectionDiagnosticsPath, runConnectionDiagnostics);
  const diagnosticsPath = el("div", "diagnostic-path", "Resolving diagnostics folder…");
  const diagnosticsDetail = el("p", "setting-copy", "Fixed read-only ADB diagnostics only · no root/su required · no pairing code or credential is intentionally recorded.");
  const openDiagnostics = button("Open diagnostics folder", "button secondary wide");
  openDiagnostics.addEventListener("click", () => {
    void invoke<string>("open_diagnostics_folder").then((path) => {
      diagnosticsPath.textContent = path;
    }).catch(() => {
      diagnosticsPath.textContent = "Could not open the diagnostics folder.";
    });
  });
  crashDiagnostics.append(diagnosticsPath, diagnosticsDetail, openDiagnostics);

  const privacy = statusCard("Privacy", "Protected", "Pairing codes are short-lived. Keyboard and clipboard contents are never kept by the desktop UI or live crash monitor.");
  cards.append(remoteMcp.element, companion, installPath, glassAtlas, phones, mcpLiveDisplay, adb, autoDetect, bridgeRecovery, crashDiagnostics, connectionDiagnostics, privacy);
  page.append(header, cards);

  if (service.mode === "real") {
    void invoke<string>("diagnostics_folder").then((path) => {
      diagnosticsPath.textContent = path;
      diagnosticsPath.setAttribute("title", "Cyclone crash-diagnostics folder");
    }).catch(() => {
      diagnosticsPath.textContent = "Diagnostics folder is unavailable.";
    });
    void invoke<string | null>("legacy_companion_warning").then((warning) => {
      if (warning) setCard(installPath, "Needs attention", warning);
    }).catch(() => { /* doctor still reports a leftover companion */ });
  } else {
    diagnosticsPath.textContent = "Available in the packaged PC Companion.";
    openDiagnostics.disabled = true;
  }

  let active = true;
  let diagnosticsTimer: number | null = null;

  const applyLiveDiagnostics = (status: DesktopRuntimeStatus): void => {
    if (!active) return;
    const live = status.liveDiagnostics;
    if (!live) {
      setCard(crashDiagnostics, "Legacy diagnostics", "This backend can save failure snapshots but does not expose the Beta 5 always-on USB monitor.");
      diagnosticsDetail.textContent = "Update both Cyclone Mobile and PC Companion to the current paired beta.";
      return;
    }

    const entries = Object.values(live.devices ?? {});
    const latest = entries.reduce((best, item) =>
      !best || Number(item.startedAtEpochMs ?? 0) > Number(best.startedAtEpochMs ?? 0) ? item : best,
    undefined as (typeof entries)[number] | undefined);

    if (live.active && live.activeDeviceCount > 0) {
      const count = live.activeDeviceCount;
      setCard(
        crashDiagnostics,
        `Monitoring ${count} phone${count === 1 ? "" : "s"}`,
        "Process-scoped logcat and lightweight state are already recording. Full Android exit/crash snapshots are collected only after a pairing failure or Cyclone process death.",
      );
      if (latest?.sessionPath) {
        diagnosticsPath.textContent = latest.sessionPath;
        diagnosticsPath.setAttribute("title", "Newest live diagnostic session");
      } else if (live.latestSessionPath) {
        diagnosticsPath.textContent = live.latestSessionPath;
      }
      const stage = latest?.lastStage || "monitor starting";
      const pid = latest?.appPid ? ` · Cyclone PID ${latest.appPid}` : " · Cyclone process not currently visible";
      diagnosticsDetail.textContent = `Last stage: ${stage}${pid}`;
    } else if ((status.discovery?.authorizedAdbDeviceCount ?? 0) > 0) {
      setCard(crashDiagnostics, "Starting monitor…", "An authorized USB phone is visible. Cyclone is attaching the process-specific Android monitor.");
      diagnosticsDetail.textContent = "The monitor starts before secure pairing and does not require root/su.";
    } else {
      setCard(crashDiagnostics, "Waiting for USB phone", "Connect and authorize USB debugging. Monitoring begins automatically before pairing.");
      diagnosticsDetail.textContent = "No root/su is required. Cyclone uses fixed read-only ADB diagnostics only.";
    }
  };

  const applyFullStatus = (status: DesktopRuntimeStatus): void => {
    if (!active) return;
    setCard(companion, status.backendReachable ? "Ready" : "Needs attention", status.message || "Desktop services are responding.");
    applyLiveDiagnostics(status);

    const discovery = status.discovery;
    if (!discovery) {
      setCard(adb, "Unavailable", "This Gateway version does not expose discovery diagnostics.");
      setCard(autoDetect, "Unavailable", "Use Scan for phones on the Phones page.");
      return;
    }

    const rawCount = discovery.rawAdbDeviceCount ?? 0;
    const fleetCount = discovery.fleetDeviceCount ?? 0;
    if (!discovery.adbAvailable) {
      const detail = discovery.lastScanError ? ` ${discovery.lastScanError}` : "";
      setCard(adb, "Needs attention", `Cyclone cannot run ADB.${detail}`.trim());
    } else if (rawCount > 0) {
      setCard(adb, `${rawCount} USB phone${rawCount === 1 ? "" : "s"} visible`, `ADB is working. Cyclone has ${fleetCount} phone${fleetCount === 1 ? "" : "s"} in its fleet.`);
    } else {
      setCard(adb, "Ready · no phone", "ADB is working, but it currently reports no connected Android phones.");
    }

    const trackerLabel = discovery.trackerActive ? "Listening" : "Fallback active";
    const source = friendlySource(discovery.lastScanSource);
    const interval = discovery.fallbackIntervalSeconds ? ` A fallback check runs every ${Math.round(discovery.fallbackIntervalSeconds)} seconds.` : "";
    setCard(autoDetect, trackerLabel, `${source}.${interval}`.trim());

    const reconnecting = discovery.reconnectingDeviceCount ?? 0;
    const attention = discovery.attentionDeviceCount ?? 0;
    const maxAttempts = discovery.maxReconnectAttempts ?? 5;
    const backoff = discovery.reconnectBackoffSeconds?.length
      ? `${discovery.reconnectBackoffSeconds.join("s · ")}s`
      : "1s → 15s";
    if (reconnecting > 0) {
      const firstError = Object.values(discovery.bridgeErrors ?? {})[0];
      const detail = firstError?.error
        ? `Last error: ${firstError.error}.`
        : `Reconnect attempts use bounded backoff (${backoff}).`;
      setCard(bridgeRecovery, `${reconnecting} phone${reconnecting === 1 ? "" : "s"} reconnecting`, `Automatic retries stop after ${maxAttempts} attempts, then the phone needs attention. ${detail}`);
    } else if (attention > 0) {
      setCard(bridgeRecovery, `${attention} phone${attention === 1 ? "" : "s"} need attention`, "Retry the connection from the Phones page or save a debug bundle to inspect the last failure.");
    } else {
      setCard(bridgeRecovery, "Stable", `Every paired phone has a healthy USB bridge. Backoff window: ${backoff}.`);
    }

    adbPath.textContent = discovery.adbPath || "";
    if (discovery.adbPath) adbPath.setAttribute("title", "ADB executable used by Cyclone");
  };

  void service.getRuntimeStatus().then(applyFullStatus).catch(() => {
    if (!active) return;
    setCard(companion, "Needs attention", "The local Cyclone Gateway isn't responding.");
    setCard(adb, "Unknown", "Cyclone could not read ADB diagnostics from the local Gateway.");
    setCard(autoDetect, "Unknown", "Restart Cyclone PC Companion and try Scan for phones again.");
    setCard(crashDiagnostics, "Unknown", "Cyclone could not confirm whether the live Android monitor is running.");
  });

  if (service.mode === "real") {
    diagnosticsTimer = window.setInterval(() => {
      void service.getRuntimeStatus().then(applyLiveDiagnostics).catch(() => { /* keep last known diagnostic state */ });
    }, 1200);
  }

  return {
    element: page,
    destroy: () => {
      active = false;
      if (diagnosticsTimer != null) window.clearInterval(diagnosticsTimer);
      diagnosticsTimer = null;
      remoteMcp.destroy();
    },
  };
}

function createRemoteMcpCard(service: DesktopService): { element: HTMLElement; destroy(): void } {
  const card = el("article", "setting-card mcp-tunnel-card");
  const top = el("div", "mcp-tunnel-top");
  const identity = el("div", "mcp-tunnel-identity");
  identity.append(
    el("div", "setting-label", MCP_TUNNEL_TITLE),
    el("p", "setting-copy mcp-tunnel-lead", MCP_TUNNEL_SUBTITLE),
  );
  const statePill = el("span", "mcp-tunnel-state state-stopped", "Stopped");
  top.append(identity, statePill);

  const statusCopy = el("p", "setting-copy", "Checking tunnel status…");
  const urlRow = el("div", "mcp-tunnel-url-grid");
  const mcpUrl = urlField("MCP URL", "Public Streamable HTTP path. Quick tunnels change hostname on every Start.");
  const healthUrl = urlField("Health URL", "Unauthenticated health check. Does not include the bearer.");
  urlRow.append(mcpUrl.wrap, healthUrl.wrap);

  const authRow = el("div", "mcp-tunnel-auth");
  const tokenValue = el("div", "mcp-tunnel-token", "Bearer · last 4 —");
  const copyToken = button("Copy token", "button secondary compact");
  const rotateToken = button("Rotate token", "button ghost compact");
  authRow.append(tokenValue, copyToken, rotateToken);

  const modeRow = el("div", "mcp-tunnel-mode");
  const readonlyBtn = button("Readonly (default)", "button secondary compact mcp-mode-readonly");
  const fullBtn = button("Full (mutating tools)", "button ghost compact mcp-mode-full");
  const modeWarning = el("p", "setting-copy mcp-tunnel-warning", MCP_TUNNEL_FULL_WARNING);
  modeWarning.hidden = true;
  modeRow.append(readonlyBtn, fullBtn);

  const actions = el("div", "mcp-tunnel-actions");
  const startBtn = button("Start tunnel", "button primary");
  const stopBtn = button("Stop tunnel", "button secondary");
  const restartBtn = button("Restart", "button ghost");
  const smokeBtn = button("Smoke", "button secondary");
  const docsBtn = button("Open connector docs", "button ghost");
  actions.append(startBtn, stopBtn, restartBtn, smokeBtn, docsBtn);

  const log = el("pre", "mcp-tunnel-log", "Tunnel log stays free of the full bearer token.");
  const checklist = el("details", "mcp-tunnel-checklist");
  const summary = el("summary", "", "ChatGPT + grok.com checklist");
  const list = el("ol", "mcp-tunnel-steps");
  for (const step of MCP_TUNNEL_CONNECTOR_CHECKLIST) list.append(el("li", "", step));
  checklist.append(summary, list, el("p", "setting-copy", MCP_TUNNEL_STDIO_NOTE));

  card.append(top, statusCopy, urlRow, authRow, modeRow, modeWarning, actions, log, checklist);

  let active = true;
  let busy = false;
  let status: McpTunnelStatus = stoppedTunnelStatus("Checking tunnel status…");
  let pollTimer: number | null = null;

  const setBusy = (next: boolean, label?: string): void => {
    busy = next;
    for (const node of [startBtn, stopBtn, restartBtn, smokeBtn, copyToken, rotateToken, readonlyBtn, fullBtn, docsBtn]) {
      node.disabled = next;
    }
    if (next && label) log.textContent = label;
  };

  const apply = (next: McpTunnelStatus): void => {
    if (!active) return;
    status = next;
    const label = friendlyTunnelState(next.state);
    statePill.textContent = label;
    statePill.className = `mcp-tunnel-state state-${next.state}`;
    statusCopy.textContent = next.message;
    mcpUrl.value.textContent = next.mcpUrl || "Not published yet";
    healthUrl.value.textContent = next.healthUrl || next.localHealthUrl;
    tokenValue.textContent = next.tokenLast4 ? `Bearer · last 4 ${next.tokenLast4}` : "Bearer · last 4 — start once to create a token";
    readonlyBtn.className = next.mode === "readonly" ? "button primary compact mcp-mode-readonly" : "button secondary compact mcp-mode-readonly";
    fullBtn.className = next.mode === "full" ? "button primary compact mcp-mode-full" : "button ghost compact mcp-mode-full";
    modeWarning.hidden = next.mode !== "full";
    startBtn.disabled = busy || next.state === "running";
    stopBtn.disabled = busy || next.state === "stopped";
    restartBtn.disabled = busy || next.state === "stopped";
  };

  const refresh = async (): Promise<void> => {
    if (!active || busy) return;
    try {
      apply(await service.getMcpTunnelStatus());
    } catch {
      if (active) apply(stoppedTunnelStatus("Could not read tunnel status. Keep Cyclone One open and retry."));
    }
  };

  const runAction = async (label: string, work: () => Promise<McpTunnelStatus>): Promise<void> => {
    if (busy) return;
    setBusy(true, label);
    try {
      const next = await work();
      apply(next);
      if (next.error) log.textContent = next.error;
      else log.textContent = next.message;
    } catch (error) {
      const message = error instanceof Error ? error.message : "Tunnel command failed.";
      log.textContent = message;
      await refresh();
    } finally {
      setBusy(false);
      startBtn.disabled = status.state === "running";
      stopBtn.disabled = status.state === "stopped";
      restartBtn.disabled = status.state === "stopped";
    }
  };

  const copyText = async (value: string, control: HTMLButtonElement, restored: string): Promise<void> => {
    try {
      await navigator.clipboard.writeText(value);
      control.textContent = "Copied";
    } catch {
      control.textContent = "Select to copy";
    }
    window.setTimeout(() => {
      if (active) control.textContent = restored;
    }, 1600);
  };

  startBtn.addEventListener("click", () => {
    void runAction("Starting tunnel…", () => service.startMcpTunnel(status.mode));
  });
  stopBtn.addEventListener("click", () => {
    void runAction("Stopping tunnel…", () => service.stopMcpTunnel());
  });
  restartBtn.addEventListener("click", () => {
    void runAction("Restarting tunnel…", () => service.restartMcpTunnel());
  });
  smokeBtn.addEventListener("click", async () => {
    if (busy) return;
    setBusy(true, "Running gateway smoke…");
    try {
      const result = await service.smokeMcpTunnel();
      log.textContent = formatSmokeLog(result);
    } catch (error) {
      log.textContent = error instanceof Error ? error.message : "Smoke failed.";
    } finally {
      setBusy(false);
      await refresh();
    }
  });
  docsBtn.addEventListener("click", async () => {
    try {
      const path = await service.openMcpTunnelDocs();
      log.textContent = `Opened connector setup notes: ${path}`;
    } catch {
      checklist.open = true;
      log.textContent = "Could not open the docs file. Use the checklist below.";
    }
  });
  copyToken.addEventListener("click", async () => {
    try {
      const secret = await service.copyMcpTunnelToken();
      await copyText(secret.token, copyToken, "Copy token");
      log.textContent = `Bearer copied (last 4 ${secret.last4}). It is not written to this log.`;
    } catch (error) {
      log.textContent = error instanceof Error ? error.message : "Could not copy the bearer token.";
    }
  });
  rotateToken.addEventListener("click", () => {
    void runAction("Rotating bearer… previous token will stop working.", () => service.rotateMcpTunnelToken());
  });
  mcpUrl.copy.addEventListener("click", () => {
    if (status.mcpUrl) void copyText(status.mcpUrl, mcpUrl.copy, "Copy");
  });
  healthUrl.copy.addEventListener("click", () => {
    const value = status.healthUrl || status.localHealthUrl;
    if (value) void copyText(value, healthUrl.copy, "Copy");
  });

  const setMode = (mode: McpTunnelMode): void => {
    if (mode === "full") {
      const confirmed = window.confirm(
        `${MCP_TUNNEL_FULL_WARNING}\n\nSwitch the public tunnel to full mode?`,
      );
      if (!confirmed) return;
    }
    void runAction(`Setting mode to ${mode}…`, () => service.setMcpTunnelMode(mode));
  };
  readonlyBtn.addEventListener("click", () => setMode("readonly"));
  fullBtn.addEventListener("click", () => setMode("full"));

  void refresh();
  pollTimer = window.setInterval(() => {
    void refresh();
  }, 4000);

  return {
    element: card,
    destroy: () => {
      active = false;
      if (pollTimer != null) window.clearInterval(pollTimer);
      pollTimer = null;
    },
  };
}

function urlField(label: string, hint: string): { wrap: HTMLElement; value: HTMLElement; copy: HTMLButtonElement } {
  const wrap = el("div", "mcp-tunnel-url");
  wrap.append(el("div", "setting-label", label));
  const value = el("div", "mcp-tunnel-url-value", "—");
  const copy = button("Copy", "button ghost compact");
  const row = el("div", "mcp-tunnel-url-row");
  row.append(value, copy);
  wrap.append(row, el("p", "setting-copy", hint));
  return { wrap, value, copy };
}

function statusCard(title: string, value: string, copy: string): HTMLElement {
  const card = el("article", "setting-card");
  card.append(el("div", "setting-label", title), el("div", "setting-value", value), el("p", "setting-copy", copy));
  return card;
}

function setCard(card: HTMLElement, value: string, copy: string): void {
  const statusValue = card.querySelector<HTMLElement>(".setting-value");
  const statusCopy = card.querySelector<HTMLElement>(".setting-copy");
  if (statusValue) statusValue.textContent = value;
  if (statusCopy) statusCopy.textContent = copy;
}

function friendlySource(source?: string): string {
  if (source === "adb-event") return "The last refresh came directly from an ADB USB device-change event";
  if (source === "manual") return "The last refresh was requested with Scan for phones";
  if (source === "startup") return "Cyclone completed its startup USB scan";
  if (source === "fallback") return "The fallback scanner refreshed the USB list";
  return "Cyclone is waiting for its first USB scan";
}
