import "./styles/tokens.css";
import "./styles/base.css";
import "./styles/components.css";
import "./styles/shell.css";
import "./styles/apps.css";
import "./styles/board.css";
import "./styles/phone.css";
import "./styles/runs.css";
import "./styles/devices.css";
import "./styles/knowledge.css";
import "./styles/lab.css";
import "./styles/market.css";
import "./styles/home.css";
import "./styles/pc.css";
import "./styles/command.css";
import "./styles/workspace.css";
import "./styles/tables.css";
import "./styles/ai.css";
import "./styles/ports.css";
import { GlassApp } from "./app.js";
import { establishSession, forgetSession } from "./core/session.js";
import { GatewayClient } from "./services/gateway.js";
import { routeHref } from "./core/router.js";
import { shortcutFor, type ShortcutState } from "./core/shortcuts.js";
import { el, setChildren } from "./ui/dom.js";
import { emptyState } from "./ui/components.js";

const root = document.getElementById("app") as HTMLElement;
const tabStorage = safe(() => window.sessionStorage);

async function boot(): Promise<void> {
  const session = await establishSession({
    hash: location.hash,
    storage: tabStorage,
    fetch: (input, init) => fetch(input, init),
    replaceHash: (hash) => history.replaceState(null, "", `${location.pathname}${location.search}${hash}`),
  });
  if (session.state !== "ready") {
    root.className = "glass-launch";
    const code = el("code", "launch-command", "cyclone");
    const body =
      session.reason === "code-rejected"
        ? "That launch link was already used or has expired. Open Glass again from the launcher."
        : session.reason === "gateway-unreachable"
          ? "Cyclone's local gateway is not running on this PC."
          : "Open Glass from the launcher so this tab gets a private session with Cyclone on this PC.";
    const box = emptyState({ icon: "plug", tone: "accent", title: "Open Cyclone Glass", body, action: code });
    setChildren(root, box);
    return;
  }
  const client = new GatewayClient({
    token: session.token,
    onSessionExpired: () => forgetSession(tabStorage),
  });
  const app = new GlassApp({
    root,
    client,
    version: __CYCLONE_GLASS_VERSION__,
    location,
    storage: tabStorage,
    setHash: (hash) => {
      location.hash = hash;
    },
    onHashChange: (listener) => {
      window.addEventListener("hashchange", listener);
      return () => window.removeEventListener("hashchange", listener);
    },
    setInterval: (fn, ms) => window.setInterval(fn, ms),
    clearInterval: (handle) => window.clearInterval(handle as number),
  });
  await app.start();
  const keys: ShortcutState = { pendingG: null };
  window.addEventListener("keydown", (event) => {
    const action = shortcutFor(event, keys, Date.now());
    if (!action) return;
    event.preventDefault();
    if (action.kind === "go") location.hash = routeHref(action.route);
    else (document.querySelector(".glass-main .search-input") as HTMLInputElement | null)?.focus();
  });
}

function safe<T>(fn: () => T): T | null {
  try {
    return fn();
  } catch {
    return null;
  }
}

void boot();
