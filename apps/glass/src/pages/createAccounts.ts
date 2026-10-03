/**
 * Create accounts (plan 43 T7): send a sign-up table's Ready rows to their phones. Pressing it shows the rows first;
 * confirming is the owner's approval of each account's final create, so the phone does not ask again.
 *
 * Passwords: the vault is unlocked here, once per batch. Each account gets a fresh generated password saved in the vault
 * (encrypted in this tab), and when the phone's task waits for it, this tab seals it to that phone for that task only.
 * The password never enters the table, the gateway in plain form, or the phone's model. The key is dropped when the
 * batch's passwords are sent, or after 15 minutes.
 */
import type { GlassContext } from "../app.js";
import { deliveryApi, sealForTask } from "../services/delivery.js";
import { signupApi, type Prepared } from "../services/signup.js";
import { decryptItem, emptyFields, encryptItem, generatePassword, newItemId, unlock, vaultApi, type OpenItem } from "../services/vault.js";
import { el, setChildren } from "../ui/dom.js";
import { actionButton } from "../ui/components.js";

const SEAL_EVERY_MS = 4_000;
const KEEP_KEY_MS = 15 * 60_000;

export interface CreateAccountsPanel {
  element: HTMLElement;
  /** Runs one sealing pass now (tests; the panel also does it every few seconds). */
  sealNow(): Promise<void>;
  destroy(): void;
}

export function createAccountsPanel(ctx: GlassContext, tableId: string, say: (text: string, tone?: "ok" | "error") => void): CreateAccountsPanel {
  const element = el("div", "ca");
  const bar = el("div", "cc-actions ca-bar");
  const sheet = el("div", "ca-sheet");
  const status = el("p", "cc-hint ca-status");
  status.setAttribute("role", "status");
  let destroyed = false;
  /** Passwords waiting to be sealed, by task: only while the batch runs. */
  let waiting = new Map<string, OpenItem>();
  let timer: ReturnType<typeof setInterval> | null = null;
  let until = 0;

  const create = actionButton("Create accounts", { variant: "primary", icon: "user" });
  create.classList.add("ca-create");
  const pause = actionButton("Pause all", { variant: "ghost", icon: "pause" });
  const cancel = actionButton("Cancel all", { variant: "ghost" });
  bar.append(create, pause, cancel);
  element.append(bar, sheet, status);

  create.addEventListener("click", async () => {
    create.disabled = true;
    try {
      const prepared = await signupApi.prepare(ctx.client, tableId);
      showSheet(prepared);
    } catch (err) {
      say((err as Error).message || "Create accounts could not start.", "error");
    } finally {
      create.disabled = false;
    }
  });
  pause.addEventListener("click", () => void stop(true));
  cancel.addEventListener("click", () => void stop(false));

  async function stop(paused: boolean): Promise<void> {
    try {
      const result = (await (paused ? signupApi.pause(ctx.client, tableId) : signupApi.cancel(ctx.client, tableId))) as { stopped?: unknown[] } | undefined;
      const n = Array.isArray(result?.stopped) ? result.stopped.length : 0;
      say(n ? `${paused ? "Paused" : "Cancelled"} ${n} ${n === 1 ? "account" : "accounts"}.${paused ? " Set a row to Ready to start it again." : ""}`
        : "Nothing was running.");
    } catch (err) {
      say((err as Error).message, "error");
    }
  }

  function showSheet(prepared: Prepared): void {
    const good = prepared.rows.filter((r) => !r.error && r.accountId);
    if (!prepared.rows.length) {
      setChildren(sheet);
      say("No row is Ready. Fill a row and set its Status to Ready.", "error");
      return;
    }
    const list = el("ul", "ca-rows");
    for (const row of prepared.rows) {
      const li = el("li", row.error ? "ca-row ca-row-error" : "ca-row");
      li.append(el("strong", undefined, row.title), el("span", "muted", row.error ?? `${row.username} · ${deviceName(row.deviceId)}`));
      list.append(li);
    }
    const pass = el("input", "cc-input");
    pass.type = "password";
    pass.autocomplete = "current-password";
    pass.setAttribute("aria-label", "Vault passphrase");
    pass.placeholder = "Vault passphrase";
    const go = actionButton(`Create ${good.length} ${good.length === 1 ? "account" : "accounts"}`, { variant: "primary" });
    go.disabled = !good.length;
    const back = actionButton("Not now", { variant: "ghost" });
    back.addEventListener("click", () => setChildren(sheet));
    go.addEventListener("click", async () => {
      go.disabled = true;
      try {
        await run(prepared, String(pass.value ?? ""));
        setChildren(sheet);
      } catch (err) {
        say((err as Error).message || "Create accounts failed.", "error");
        go.disabled = false;
      } finally {
        pass.value = "";
      }
    });
    const actions = el("div", "cc-actions");
    actions.append(pass, go, back);
    setChildren(sheet,
      el("h4", "ca-title", `Create ${prepared.app} accounts`),
      list,
      el("p", "cc-hint", "Confirming approves creating each of these accounts: the phone presses the final button without asking again. " +
        "Each account gets a new password in your vault. A code, CAPTCHA or ID check still waits for a person."),
      actions);
  }

  async function run(prepared: Prepared, passphrase: string): Promise<void> {
    if (!passphrase) throw new Error("Type the vault passphrase: each account's password is made in the vault.");
    const state = await vaultApi.get(ctx.client);
    if (!state.exists || !state.meta) throw new Error("Set up the vault first (Command Center → Vault).");
    const vk = await unlock(state.meta, { passphrase });
    const items = new Map<string, OpenItem>();
    for (const row of prepared.rows) {
      if (row.error || !row.accountId) continue;
      const earlier = row.vaultItemId ? state.items.find((i) => i.id === row.vaultItemId) : undefined;
      if (earlier) {
        items.set(row.rowId, await decryptItem(vk, earlier));
        continue;
      }
      const fields = { ...emptyFields(), label: `${prepared.app} · ${row.title}`, username: row.username, secret: generatePassword(20), accountId: row.accountId };
      const saved = await vaultApi.put(ctx.client, await encryptItem(vk, { id: newItemId(), kind: "login", version: 1 }, fields));
      fields.secret = "";
      items.set(row.rowId, await decryptItem(vk, saved));
    }
    const result = await signupApi.create(ctx.client, prepared.tableId,
      [...items.entries()].map(([rowId, item]) => ({ rowId, accountId: item.accountId ?? "", vaultItemId: item.id })));
    for (const started of result.started) {
      const item = items.get(started.rowId);
      if (item) waiting.set(started.taskId, item);
    }
    const failed = result.errors.length ? ` ${result.errors.length} could not start: ${result.errors.map((e) => e.error).join(" ")}` : "";
    say(`Started ${result.started.length} ${result.started.length === 1 ? "account" : "accounts"}. Keep this page open while their passwords are sent.${failed}`,
      result.errors.length ? "error" : "ok");
    until = Date.now() + KEEP_KEY_MS;
    timer ??= setInterval(() => void seal(), SEAL_EVERY_MS);
    void seal();
  }

  /** Seal each waiting password to its phone as soon as that task asks for it. */
  async function seal(): Promise<void> {
    if (destroyed) return;
    if (!waiting.size || Date.now() > until) return drop();
    status.textContent = `Sending passwords: ${waiting.size} waiting for their phone.`;
    let pending;
    try {
      pending = await deliveryApi.pending(ctx.client);
    } catch {
      return;
    }
    for (const p of pending) {
      const item = waiting.get(p.taskId);
      if (!item) continue;
      if (!p.deviceKey) {
        status.textContent = "Trust the phone's key first (Command Center → Vault → Phones).";
        continue;
      }
      try {
        await deliveryApi.submit(ctx.client, p.taskId, await sealForTask(p, item));
        waiting.delete(p.taskId);
      } catch (err) {
        say((err as Error).message, "error");
      }
    }
    if (!waiting.size) drop();
  }

  function drop(): void {
    if (timer) clearInterval(timer);
    timer = null;
    for (const item of waiting.values()) item.secret = "";
    waiting = new Map();
    status.textContent = "";
  }

  function deviceName(id: string): string {
    return ctx.devices.find((d) => d.id === id)?.name ?? id;
  }

  return {
    element,
    sealNow: () => seal(),
    destroy() {
      destroyed = true;
      drop();
    },
  };
}
