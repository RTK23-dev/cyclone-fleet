/**
 * Ask from Glass: the developer types the sentence, the phone's own engine runs it (`ask.start`), and Glass mirrors
 * the phone's presentation snapshot (`ask.status`). Glass never plans, rewrites or retries the goal.
 */
import type { AskStatusView, AtlasClient } from "../services/atlasClient.js";
import { ASK_MAX_GOAL } from "../services/atlasClient.js";
import { el, link, setChildren } from "../ui/dom.js";
import { actionButton, chip, type Tone } from "../ui/components.js";

export interface AskPanelDeps {
  phone: Pick<AtlasClient, "askStart" | "askStatus">;
  setTimer?: (fn: () => void, ms: number) => unknown;
  clearTimer?: (handle: unknown) => void;
  intervalMs?: number;
  /** The newest run on the phone that started at or after `since` (ms); lets a finished Ask link to its inspector. */
  latestRun?: (since: number) => Promise<string | null>;
  /** Recent distinct sentences, newest first; clicking one fills the box (it never sends by itself). */
  recentGoals?: () => Promise<string[]>;
  now?: () => number;
}

export interface AskPanel {
  element: HTMLElement;
  destroy(): void;
}

const TERMINAL = new Set<AskStatusView["state"]>(["idle", "done", "failed"]);

const STATE_CHIP: Record<AskStatusView["state"], [string, Tone]> = {
  idle: ["Idle", "neutral"],
  working: ["Working", "accent"],
  "action-needed": ["Needs you", "warning"],
  "needs-secret": ["Waiting for a password on the phone", "warning"],
  done: ["Done", "success"],
  failed: ["Stopped", "danger"],
};

export function askErrorCopy(code: string, message: string): string {
  switch (code) {
    case "ASK_BUSY":
      return "The phone is already running a task. Wait for it or stop it on the phone.";
    case "HUMAN_HAS_CONTROL":
      return "You have control of the phone. Give it back to Cyclone, then ask.";
    case "OVERLAY_UNAVAILABLE":
      return "Turn on Cyclone's accessibility service on the phone.";
    case "SECRET_PAYLOAD_REJECTED":
    case "INVALID_REQUEST":
      return "Keep passwords out of the goal. Cyclone asks for them on the phone.";
    default:
      return message || `Ask couldn't start (${code}).`;
  }
}

export function createAskPanel(deps: AskPanelDeps): AskPanel {
  const setTimer = deps.setTimer ?? ((fn: () => void, ms: number) => setTimeout(fn, ms));
  const clearTimer = deps.clearTimer ?? ((handle: unknown) => clearTimeout(handle as ReturnType<typeof setTimeout>));
  const intervalMs = deps.intervalMs ?? 1_500;

  const element = el("section", "card ask-panel");
  element.append(el("h2", "card-title", "Ask Cyclone"));
  element.append(el("p", "muted", "The phone runs your sentence exactly as typed. Glass only shows what happens."));
  const form = el("form", "ask-form");
  const input = el("textarea", "ask-input");
  input.placeholder = "open Gmail, check my current logged in email, then find the DM of Louella on Facebook";
  input.setAttribute("aria-label", "Goal for Cyclone");
  input.maxLength = ASK_MAX_GOAL;
  input.rows = 3;
  const send = actionButton("Send to phone", { icon: "send", variant: "primary" });
  send.type = "submit";
  const note = el("p", "ask-note");
  note.setAttribute("role", "status");
  form.append(input, send, note);
  const recent = el("div", "ask-recent");
  const hud = el("div", "ask-hud");
  element.append(form, recent, hud);
  void deps.recentGoals?.()
    .then((goals) => {
      if (destroyed || !goals.length) return;
      const chips = goals.slice(0, 5).map((goal) => {
        const button = el("button", "ask-recent-goal", goal.length > 60 ? `${goal.slice(0, 57)}…` : goal);
        button.type = "button";
        button.title = goal;
        button.addEventListener("click", () => {
          input.value = goal;
          input.focus?.();
        });
        return button;
      });
      setChildren(recent, el("span", "muted", "Recent:"), ...chips);
    })
    .catch(() => undefined);

  let timer: unknown = null;
  let destroyed = false;
  let busy = false;
  let askedAt: number | null = null;
  let runLink: { askedAt: number; runId: string } | null = null;
  let looking = false;
  const now = deps.now ?? Date.now;

  /** Once per Ask: find its run so the link opens the inspector, not the whole list. */
  const findRun = (): void => {
    const since = askedAt;
    if (since === null || !deps.latestRun || runLink?.askedAt === since || looking) return;
    looking = true;
    void deps.latestRun(since - 5_000).finally(() => {
      looking = false;
    }).then((runId) => {
      if (destroyed || !runId || askedAt !== since) return;
      runLink = { askedAt: since, runId };
      const terminal = TERMINAL.has((hud.dataset.state ?? "idle") as AskStatusView["state"]);
      const current = hud.querySelector(".ask-runs-link") as HTMLAnchorElement | null;
      const text = terminal ? "Open this run" : "Watch it step by step";
      if (current) {
        current.textContent = text;
        current.href = `#/runs/${encodeURIComponent(runId)}`;
      } else if (hud.children.length) {
        hud.append(link(text, `#/runs/${encodeURIComponent(runId)}`, "ask-runs-link"));
      }
    }).catch(() => undefined);
  };

  const renderStatus = (status: AskStatusView): void => {
    if (status.state === "idle") {
      setChildren(hud);
      return;
    }
    const [label, tone] = STATE_CHIP[status.state];
    const head = el("div", "ask-hud-head");
    head.append(el("span", "ask-hud-title", status.title || "Cyclone task"), chip(label, tone));
    const steps = el("ol", "ask-steps");
    for (const milestone of status.milestones) {
      const step = el("li", `ask-step step-${milestone.state}`, milestone.label);
      steps.append(step);
    }
    setChildren(
      hud,
      head,
      status.app ? el("p", "muted", status.app) : null,
      status.milestones.length ? steps : null,
      status.supportingCopy ? el("p", "ask-copy", status.supportingCopy) : null,
      status.outcomeCopy ? el("p", "ask-outcome", status.outcomeCopy) : null,
      runLink && runLink.askedAt === askedAt
        ? link(TERMINAL.has(status.state) ? "Open this run" : "Watch it step by step", `#/runs/${encodeURIComponent(runLink.runId)}`, "ask-runs-link")
        : TERMINAL.has(status.state)
          ? link("See every step in Runs", "#/runs", "ask-runs-link")
          : null,
    );
    hud.dataset.state = status.state;
    findRun();
  };

  const schedule = (): void => {
    if (destroyed) return;
    timer = setTimer(() => void poll(), intervalMs);
  };

  const poll = async (): Promise<void> => {
    timer = null;
    try {
      const status = await deps.phone.askStatus();
      if (destroyed) return;
      renderStatus(status);
      if (!TERMINAL.has(status.state)) schedule();
    } catch {
      if (!destroyed) schedule();
    }
  };

  form.addEventListener("submit", (event: Event) => {
    event.preventDefault?.();
    const goal = input.value.trim();
    if (!goal || busy) return;
    busy = true;
    send.disabled = true;
    note.textContent = "";
    void deps.phone
      .askStart(goal)
      .then(() => {
        askedAt = now();
        input.value = "";
        if (timer !== null) clearTimer(timer);
        return poll();
      })
      .catch((error: { code?: string; message?: string }) => {
        note.textContent = askErrorCopy(error?.code ?? "", error?.message ?? "");
      })
      .finally(() => {
        busy = false;
        send.disabled = false;
      });
  });

  void poll(); // follow a run that was started on the phone

  return {
    element,
    destroy() {
      destroyed = true;
      if (timer !== null) clearTimer(timer);
    },
  };
}
