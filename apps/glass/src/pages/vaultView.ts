/**
 * The Vault tab of the Command Center (plan 33, C1). Everything secret is encrypted in this tab before it is sent,
 * and decrypted here only while the vault is unlocked. Locking (the button, 5 minutes idle, or leaving the tab) drops
 * the key and every opened item from memory.
 *
 * Revealing or copying a secret needs the passphrase again when the last proof is older than 2 minutes.
 */
import type { GlassContext } from "../app.js";
import type { CcAccount } from "../services/command.js";
import {
  ITEM_KINDS, MIN_PASSPHRASE, VaultLockedError, backupFile, createVault, decryptItem, emptyFields, encryptItem,
  generatePassword, health, newItemId, newRecoveryKey, rewrapPassphrase, strength, totpCode, unlock, vaultApi,
  type ItemFields, type ItemKind, type OpenItem, type Unlocker, type VaultRecord, type VaultState,
} from "../services/vault.js";
import { parseExport, type ImportResult } from "../services/vaultImport.js";
import { deliveryApi, leaseStateLabel, placeLabel, sealForTask, type Lease, type PendingLease, type Phone } from "../services/delivery.js";
import { fingerprint } from "../services/hpke.js";
import { fromB64 } from "../services/vault.js";
import { copyText } from "../ui/clipboard.js";
import { actionButton, card, chip, emptyState, errorState, loadingState } from "../ui/components.js";
import { el, setChildren } from "../ui/dom.js";
import { relativeTime } from "../ui/format.js";

const IDLE_LOCK_MS = 5 * 60_000;
const STEP_UP_MS = 2 * 60_000;
const REVEAL_MS = 20_000;
const CLIPBOARD_CLEAR_MS = 30_000;

const KIND_LABEL: Record<ItemKind, string> = { login: "Login", totp: "Authenticator", recovery_codes: "Recovery codes", note: "Secure note" };

export interface VaultView {
  element: HTMLElement;
  destroy(): void;
}

export function createVaultView(ctx: GlassContext, accounts: () => CcAccount[], say: (text: string, tone?: "ok" | "error") => void): VaultView {
  const element = el("div", "vault");
  let state: VaultState | null = null;
  let vk: CryptoKey | null = null;
  let items: OpenItem[] = [];
  let lastProof = 0;
  let idle: ReturnType<typeof setTimeout> | null = null;
  let editing: OpenItem | "new" | null = null;
  let query = "";
  let destroyed = false;
  const revealed = new Set<string>();
  const timers = new Set<ReturnType<typeof setTimeout>>();

  const later = (fn: () => void, ms: number) => {
    const t = setTimeout(() => {
      timers.delete(t);
      fn();
    }, ms);
    timers.add(t);
  };

  function touch(): void {
    if (!vk) return;
    if (idle) clearTimeout(idle);
    idle = setTimeout(() => lock("Locked after 5 minutes without use."), IDLE_LOCK_MS);
  }
  element.addEventListener("pointerdown", touch);
  element.addEventListener("keydown", touch);

  function lock(message = "Vault locked."): void {
    if (vk) void vaultApi.audit(ctx.client, "lock");
    vk = null;
    items = [];
    revealed.clear();
    editing = null;
    lastProof = 0;
    if (idle) clearTimeout(idle);
    idle = null;
    if (deliveryTimer) clearTimeout(deliveryTimer);
    if (!destroyed) {
      say(message);
      render();
    }
  }

  async function load(): Promise<void> {
    setChildren(element, loadingState("Opening the vault…"));
    try {
      state = await vaultApi.get(ctx.client);
      if (vk && state.exists) items = await openAll(vk, state.items);
      render();
    } catch (error) {
      setChildren(element, errorState("The vault is not reachable", error as Error, () => void load()));
    }
  }

  async function openAll(key: CryptoKey, records: VaultRecord[]): Promise<OpenItem[]> {
    const out: OpenItem[] = [];
    for (const record of records) {
      try {
        out.push(await decryptItem(key, record));
      } catch {
        say("One item could not be opened: it was changed outside Glass.", "error");
      }
    }
    return out;
  }

  function render(): void {
    if (destroyed || !state) return;
    if (!state.exists) setChildren(element, createCard(), restoreCard());
    else if (!vk) setChildren(element, unlockCard());
    else setChildren(element, unlockedView());
  }

  // ------------------------------------------------------------------ create, restore, recovery key

  function createCard(): HTMLElement {
    const box = card("cc-card vault-card");
    box.append(
      el("h2", "card-title", "Create your vault"),
      el("p", "cc-hint", "Your passwords are encrypted in this browser with a passphrase only you know. This PC stores them encrypted; nobody can open them without your passphrase or recovery key, not even Cyclone."),
    );
    const pass = secretInput("Passphrase");
    const again = secretInput("Passphrase again");
    const meter = el("p", "cc-hint");
    pass.addEventListener("input", () => {
      const value = String(pass.value ?? "");
      meter.textContent = value ? `Strength: ${strength(value)}. Four or five unrelated words work well.` : "";
    });
    const create = actionButton("Create vault", { variant: "primary", icon: "lock" });
    create.addEventListener("click", async () => {
      const a = String(pass.value ?? "");
      if (a !== String(again.value ?? "")) return say("The two passphrases differ.", "error");
      create.disabled = true;
      say("Creating the vault…");
      try {
        const made = await createVault(a);
        state = await vaultApi.init(ctx.client, made.init);
        pass.value = "";
        again.value = "";
        vk = made.vk;
        lastProof = Date.now();
        items = [];
        touch();
        setChildren(element, recoveryCard(made.recoveryKey, () => render()));
        say("Vault created.");
      } catch (error) {
        say((error as Error).message, "error");
      } finally {
        create.disabled = false;
      }
    });
    box.append(labelled("Passphrase", pass), labelled("Passphrase again", again), meter, row(create));
    return box;
  }

  function recoveryCard(key: string, done: () => void): HTMLElement {
    const box = card("cc-card vault-card");
    box.append(
      el("h2", "card-title", "Save your recovery key"),
      el("p", "cc-hint", "If you forget your passphrase, this key is the only other way in. Glass shows it once and does not keep it. Print it or keep it somewhere safe, away from this PC."),
      el("code", "vault-recovery", key),
    );
    const copy = actionButton("Copy", { icon: "download", variant: "ghost" });
    copy.addEventListener("click", () => void copyText(key).then((ok) => say(ok ? "Recovery key copied." : "Copy it by hand.")));
    const kit = actionButton("Download recovery kit", { icon: "download", variant: "ghost" });
    kit.addEventListener("click", () => download("Cyclone-recovery-kit.txt", recoveryKit(key)));
    const saved = el("input");
    saved.type = "checkbox";
    const savedLabel = el("label", "cc-check");
    savedLabel.append(saved, el("span", undefined, "I saved my recovery key"));
    const next = actionButton("Continue", { variant: "primary" });
    next.addEventListener("click", () => {
      if (!saved.checked) return say("Tick the box once the key is saved.", "error");
      done();
    });
    box.append(row(copy, kit), savedLabel, row(next));
    return box;
  }

  function restoreCard(): HTMLElement {
    const box = card("cc-card vault-card");
    box.append(el("h2", "card-title", "Or restore an encrypted backup"), el("p", "cc-hint", "A backup made in Glass opens with the passphrase or recovery key it was made with."));
    const file = fileInput(".json,application/json", "Backup file");
    const restore = actionButton("Restore", { icon: "refresh" });
    restore.addEventListener("click", async () => {
      const picked = file.files?.[0];
      if (!picked) return say("Pick the backup file first.", "error");
      try {
        const backup = JSON.parse(await picked.text());
        if (backup?.cyclone !== "vault-backup") throw new Error("That file is not a Cyclone vault backup.");
        state = await vaultApi.restore(ctx.client, backup);
        say(`Restored ${state.items.length} items. Unlock with the backup's passphrase.`);
        render();
      } catch (error) {
        say((error as Error).message, "error");
      }
    });
    box.append(labelled("Backup file", file), row(restore));
    return box;
  }

  // ------------------------------------------------------------------ unlock

  function unlockCard(): HTMLElement {
    const box = card("cc-card vault-card");
    let recovery = false;
    const title = el("h2", "card-title", "Unlock the vault");
    const input = secretInput("Passphrase");
    const field = labelled("Passphrase", input);
    const go = actionButton("Unlock", { variant: "primary", icon: "lock" });
    const toggle = actionButton("Use recovery key instead", { variant: "ghost" });
    toggle.addEventListener("click", () => {
      recovery = !recovery;
      input.value = "";
      input.type = recovery ? "text" : "password";
      input.setAttribute("aria-label", recovery ? "Recovery key" : "Passphrase");
      field.querySelector(".cc-field-label")!.textContent = recovery ? "Recovery key" : "Passphrase";
      toggle.querySelector(".btn-label")!.textContent = recovery ? "Use passphrase instead" : "Use recovery key instead";
    });
    const submit = async () => {
      const value = String(input.value ?? "");
      if (!value || !state?.meta) return;
      go.disabled = true;
      say("Unlocking…");
      try {
        const unlocker: Unlocker = recovery ? { recoveryKey: value } : { passphrase: value };
        vk = await unlock(state.meta, unlocker);
        input.value = "";
        lastProof = Date.now();
        items = await openAll(vk, state.items);
        touch();
        void vaultApi.audit(ctx.client, recovery ? "unlock_recovery" : "unlock");
        say(recovery ? "Unlocked with the recovery key. Set a new passphrase under Settings below." : "Unlocked.");
        render();
      } catch (error) {
        if (error instanceof VaultLockedError) void vaultApi.audit(ctx.client, "unlock_failed");
        say((error as Error).message, "error");
      } finally {
        go.disabled = false;
      }
    };
    go.addEventListener("click", () => void submit());
    input.addEventListener("keydown", (event) => {
      if ((event as KeyboardEvent).key === "Enter") void submit();
    });
    const reset = actionButton("Forgot both? Delete the vault", { variant: "ghost" });
    reset.addEventListener("click", () => {
      const confirm = el("input", "cc-input");
      confirm.placeholder = "Type DELETE VAULT";
      confirm.setAttribute("aria-label", "Type DELETE VAULT");
      const really = actionButton("Delete the vault and every item", { variant: "danger" });
      really.addEventListener("click", async () => {
        if (String(confirm.value ?? "") !== "DELETE VAULT") return say("Type DELETE VAULT exactly.", "error");
        state = await vaultApi.reset(ctx.client);
        say("The vault was deleted.");
        render();
      });
      box.append(row(confirm, really));
    });
    box.append(title, el("p", "cc-hint", `${state?.items.length ?? 0} items, encrypted. Glass keeps nothing after you lock or leave.`), field, row(go, toggle), row(reset));
    return box;
  }

  async function prove(): Promise<boolean> {
    if (vk && Date.now() - lastProof < STEP_UP_MS) return true;
    return new Promise((resolve) => {
      const box = card("cc-card vault-stepup");
      const input = secretInput("Passphrase");
      const ok = actionButton("Confirm", { variant: "primary" });
      const cancel = actionButton("Cancel", { variant: "ghost" });
      const finish = (value: boolean) => {
        box.remove();
        resolve(value);
      };
      ok.addEventListener("click", async () => {
        try {
          await unlock(state!.meta!, { passphrase: String(input.value ?? "") });
          lastProof = Date.now();
          finish(true);
        } catch (error) {
          say((error as Error).message, "error");
        }
      });
      cancel.addEventListener("click", () => finish(false));
      box.append(el("p", "cc-hint", "Enter your passphrase to show or copy secrets."), labelled("Passphrase", input), row(ok, cancel));
      element.insertBefore(box, element.firstChild);
    });
  }

  // ------------------------------------------------------------------ unlocked

  function unlockedView(): HTMLElement {
    const wrap = el("div", "vault-open");
    const bar = el("div", "cc-actions");
    const add = actionButton("Add item", { variant: "primary", icon: "lock" });
    add.addEventListener("click", () => {
      editing = "new";
      render();
    });
    const lockButton = actionButton("Lock", { icon: "lock", variant: "secondary" });
    lockButton.addEventListener("click", () => lock());
    const search = el("input", "cc-input");
    search.placeholder = "Search the vault";
    search.setAttribute("aria-label", "Search the vault");
    search.value = query;
    search.addEventListener("input", () => {
      query = String(search.value ?? "");
      setChildren(list, itemsTable());
    });
    bar.append(add, search, lockButton);
    const h = health(items);
    const summary = el("div", "cc-row vault-health");
    summary.append(
      chip(`${items.length} items`, "neutral"),
      chip(`${h.weak.length} weak`, h.weak.length ? "warning" : "success"),
      chip(`${h.reused.length} reused`, h.reused.length ? "warning" : "success"),
      chip(`${h.old.length} older than a year`, h.old.length ? "warning" : "success"),
    );
    const list = el("div");
    list.append(itemsTable());
    wrap.append(bar, summary);
    if (editing) wrap.append(editor(editing === "new" ? null : editing));
    wrap.append(list, deliveryCard(), phonesCard(), importCard(), settingsCard());
    return wrap;
  }

  function itemsTable(): HTMLElement {
    const q = query.trim().toLowerCase();
    const shown = items.filter((i) => !q || [i.label, i.username, i.url, i.notes].some((v) => v.toLowerCase().includes(q)));
    if (!items.length) return emptyState({ icon: "lock", title: "The vault is empty", body: "Add a login, an authenticator seed, recovery codes or a note, or import from another password manager." });
    const h = health(items);
    const table = el("table", "cc-table");
    const head = el("tr");
    for (const label of ["Item", "Kind", "Account", "Secret", "Health", "Updated", ""]) head.append(el("th", undefined, label));
    table.append(head);
    for (const item of shown) {
      const tr = el("tr");
      const name = el("td");
      name.append(el("span", undefined, item.label || "(no name)"), el("span", "cc-sub", item.username || item.url));
      const secretCell = el("td", "vault-secret");
      secretCell.append(el("span", "vault-mask", revealed.has(item.id) ? item.secret || "—" : item.secret ? "••••••••••" : "—"));
      const healthCell = el("td");
      if (item.moved) healthCell.append(chip("Account changed outside Glass", "danger"));
      if (h.weak.includes(item.id)) healthCell.append(chip("Weak", "warning"));
      if (h.reused.includes(item.id)) healthCell.append(chip("Reused", "warning"));
      if (h.old.includes(item.id)) healthCell.append(chip("Old", "neutral"));
      const actions = el("td", "cc-row");
      if (item.secret) {
        const reveal = actionButton(revealed.has(item.id) ? "Hide" : "Show", { variant: "ghost" });
        reveal.addEventListener("click", async () => {
          if (revealed.has(item.id)) {
            revealed.delete(item.id);
            return render();
          }
          if (!(await prove())) return;
          revealed.add(item.id);
          void vaultApi.audit(ctx.client, "reveal", item.id);
          render();
          later(() => {
            revealed.delete(item.id);
            render();
          }, REVEAL_MS);
        });
        const copy = actionButton("Copy", { variant: "ghost" });
        copy.addEventListener("click", async () => {
          if (!(await prove())) return;
          const ok = await copyText(item.secret);
          void vaultApi.audit(ctx.client, "copy", item.id);
          say(ok ? "Copied. The clipboard is cleared in 30 seconds." : "The browser refused the clipboard.", ok ? "ok" : "error");
          if (ok) later(() => void copyText(""), CLIPBOARD_CLEAR_MS);
        });
        actions.append(reveal, copy);
      }
      if (item.totp || item.kind === "totp") {
        const code = actionButton("Code", { variant: "ghost" });
        code.addEventListener("click", async () => {
          if (!(await prove())) return;
          try {
            const value = await totpCode(item.kind === "totp" ? item.secret || item.totp : item.totp);
            await copyText(value);
            void vaultApi.audit(ctx.client, "copy", item.id);
            say(`Code ${value} copied.`);
          } catch (error) {
            say((error as Error).message, "error");
          }
        });
        actions.append(code);
      }
      const edit = actionButton("Edit", { variant: "ghost" });
      edit.addEventListener("click", async () => {
        if (!(await prove())) return;
        editing = item;
        render();
      });
      const remove = actionButton("Delete", { variant: "ghost" });
      remove.addEventListener("click", async () => {
        try {
          await vaultApi.remove(ctx.client, item.id);
          items = items.filter((i) => i.id !== item.id);
          if (state) state = { ...state, items: state.items.filter((r) => r.id !== item.id) };
          say("Deleted.");
          render();
        } catch (error) {
          say((error as Error).message, "error");
        }
      });
      actions.append(edit, remove);
      tr.append(name, cellText(KIND_LABEL[item.kind]), cellText(accountName(item.accountId)), secretCell, healthCell, cellText(relativeTime(item.updatedAt)), actions);
      table.append(tr);
    }
    return table;
  }

  function accountName(id: string | null): string {
    if (!id) return "—";
    const account = accounts().find((a) => a.id === id);
    return account ? `${account.handle} · ${account.service}` : "Removed account";
  }

  function editor(item: OpenItem | null): HTMLElement {
    const box = card("cc-card vault-editor");
    box.append(el("h2", "card-title", item ? `Edit ${item.label || "item"}` : "New item"));
    const kind = el("select", "cc-input");
    kind.setAttribute("aria-label", "Kind");
    for (const k of ITEM_KINDS) {
      const option = el("option", undefined, KIND_LABEL[k]);
      option.value = k;
      option.selected = (item?.kind ?? "login") === k;
      kind.append(option);
    }
    if (item) kind.disabled = true;
    const account = el("select", "cc-input");
    account.setAttribute("aria-label", "Account");
    const none = el("option", undefined, "No account");
    none.value = "";
    account.append(none);
    for (const a of accounts()) {
      const option = el("option", undefined, `${a.handle} · ${a.service}`);
      option.value = a.id;
      option.selected = item?.accountId === a.id;
      account.append(option);
    }
    const label = textInput("Name", item?.label);
    const username = textInput("Username or email", item?.username);
    const secret = secretInput("Password, seed or codes");
    secret.value = item?.secret ?? "";
    const url = textInput("Website or app", item?.url);
    const totp = textInput("Authenticator seed (optional)", item?.totp);
    const notes = el("textarea", "cc-input cc-goal");
    notes.setAttribute("aria-label", "Notes");
    notes.value = item?.notes ?? "";
    const show = actionButton("Show", { variant: "ghost" });
    show.addEventListener("click", () => {
      secret.type = secret.type === "password" ? "text" : "password";
    });
    const generate = actionButton("Generate", { variant: "ghost" });
    generate.addEventListener("click", () => {
      secret.value = generatePassword(20);
      secret.type = "text";
    });
    const save = actionButton("Save", { variant: "primary" });
    save.addEventListener("click", async () => {
      if (!vk) return;
      const fields: ItemFields = {
        ...emptyFields(), label: String(label.value ?? ""), username: String(username.value ?? ""), secret: String(secret.value ?? ""),
        url: String(url.value ?? ""), totp: String(totp.value ?? ""), notes: String(notes.value ?? ""), accountId: String(account.value || "") || null,
      };
      if (!fields.label.trim()) return say("Give the item a name.", "error");
      try {
        const head = item ? { id: item.id, kind: item.kind, version: item.version + 1 } : { id: newItemId(), kind: String(kind.value || "login") as ItemKind, version: 1 };
        const saved = await vaultApi.put(ctx.client, await encryptItem(vk, head, fields));
        secret.value = "";
        const opened = await decryptItem(vk, saved);
        items = [...items.filter((i) => i.id !== opened.id), opened];
        if (state) state = { ...state, items: [...state.items.filter((r) => r.id !== saved.id), saved] };
        editing = null;
        say("Saved, encrypted.");
        render();
      } catch (error) {
        say((error as Error).message, "error");
      }
    });
    const cancel = actionButton("Cancel", { variant: "ghost" });
    cancel.addEventListener("click", () => {
      editing = null;
      render();
    });
    const grid = el("div", "cc-grid");
    grid.append(labelled("Kind", kind), labelled("Account", account), labelled("Name", label), labelled("Username or email", username), labelled("Website or app", url), labelled("Authenticator seed (optional)", totp));
    box.append(grid, labelled("Password, seed or codes", secret), row(show, generate), labelled("Notes", notes), row(save, cancel));
    return box;
  }

  // ------------------------------------------------------------------ sealed delivery (C2)

  let autoSend = true;
  const tried = new Map<string, number>();
  let deliveryBox: HTMLElement | null = null;
  let phonesBox: HTMLElement | null = null;

  /** Seal every waiting task's secret this tab can: the vault is unlocked and the phone's key is trusted. */
  async function sendPending(pending: PendingLease[], only?: string): Promise<void> {
    if (!vk) return;
    for (const p of pending) {
      if (only && p.taskId !== only) continue;
      if (!p.deviceKey) continue;
      if (!only && Date.now() - (tried.get(p.taskId) ?? 0) < 60_000) continue;
      tried.set(p.taskId, Date.now());
      const item = items.find((i) => i.id === p.vaultItemId);
      if (!item) {
        say(`"${p.title}" uses a vault item this vault does not have.`, "error");
        continue;
      }
      try {
        const envelopes = await sealForTask(p, item);
        await deliveryApi.submit(ctx.client, p.taskId, envelopes);
        say(`Sealed for "${p.title}": only that phone can open it, for this task, on ${placeLabel(p.place)}.`);
      } catch (error) {
        say((error as Error).message, "error");
      }
    }
  }

  async function refreshDelivery(): Promise<void> {
    if (!vk || !deliveryBox || destroyed) return;
    try {
      let [pending, leases] = await Promise.all([deliveryApi.pending(ctx.client), deliveryApi.leases(ctx.client)]);
      if (autoSend && pending.some((p) => p.deviceKey && Date.now() - (tried.get(p.taskId) ?? 0) >= 60_000)) {
        await sendPending(pending);
        [pending, leases] = await Promise.all([deliveryApi.pending(ctx.client), deliveryApi.leases(ctx.client)]);
      }
      renderDelivery(pending, leases);
    } catch (error) {
      setChildren(deliveryBox, el("p", "cc-hint", (error as Error).message));
    }
    refreshDeliveryLater();
  }

  let deliveryTimer: ReturnType<typeof setTimeout> | null = null;
  function refreshDeliveryLater(): void {
    if (deliveryTimer) clearTimeout(deliveryTimer);
    deliveryTimer = setTimeout(() => void refreshDelivery(), 10_000);
  }

  function renderDelivery(pending: PendingLease[], leases: Lease[]): void {
    if (!deliveryBox) return;
    const auto = el("input");
    auto.type = "checkbox";
    auto.checked = autoSend;
    auto.addEventListener("change", () => {
      autoSend = auto.checked;
    });
    const autoLabel = el("label", "cc-check");
    autoLabel.append(auto, el("span", undefined, "Send automatically while this tab is open and the vault unlocked"));
    const rows: HTMLElement[] = [];
    for (const p of pending) {
      const row = el("div", "cc-row vault-pending");
      const when = p.ahead && p.dueAt ? ` · prepared for the run at ${new Date(p.dueAt).toLocaleString()}` : "";
      row.append(el("strong", undefined, p.title), el("span", "muted", `${p.handle} · ${placeLabel(p.place)}${when}`));
      if (!p.deviceKey) row.append(chip("Trust the phone first", "warning"));
      else {
        const send = actionButton("Seal and send", { variant: "primary" });
        send.addEventListener("click", () => void sendPending([p], p.taskId).then(() => refreshDelivery()));
        row.append(send);
      }
      rows.push(row);
    }
    const recent = leases.slice(0, 8).map((l) => {
      const state = leaseStateLabel(l.state);
      const row = el("div", "cc-row");
      row.append(chip(state.label, state.tone), el("span", "muted", `${l.slot === "otp" ? "Authenticator code" : "Password"} · ${placeLabel(l.place)} · ${relativeTime(l.createdAt)}`));
      if (l.state === "ready") {
        const revoke = actionButton("Revoke", { variant: "ghost" });
        revoke.addEventListener("click", () => void deliveryApi.revoke(ctx.client, l.id).then(() => refreshDelivery()));
        row.append(revoke);
      }
      return row;
    });
    setChildren(deliveryBox,
      el("h2", "card-title", "Passwords for tasks"),
      el("p", "cc-hint", "A task that signs in with a vault login waits here. Glass seals the password to that one phone, for that one task, on that one app or site, for 30 minutes; the phone uses it once and wipes it. The PC only passes the sealed bytes on."),
      autoLabel,
      ...(rows.length ? rows : [el("p", "cc-hint", "Nothing is waiting.")]),
      ...(recent.length ? [el("h3", "card-subtitle", "Recent"), ...recent] : []),
    );
  }

  function deliveryCard(): HTMLElement {
    deliveryBox = card("cc-card vault-card");
    deliveryBox.append(loadingState("Checking tasks…"));
    void refreshDelivery();
    return deliveryBox;
  }

  async function refreshPhones(): Promise<void> {
    if (!phonesBox) return;
    let phones: Phone[] = [];
    try {
      phones = await deliveryApi.phones(ctx.client);
    } catch (error) {
      setChildren(phonesBox, el("p", "cc-hint", (error as Error).message));
      return;
    }
    const rows: HTMLElement[] = [];
    for (const phone of phones) {
      const row = el("div", "vault-phone");
      const head = el("div", "cc-row");
      head.append(el("strong", undefined, phone.name), el("span", "muted", phone.ready ? "connected" : "not connected"));
      row.append(head);
      if (!phone.key) {
        const get = actionButton("Get its key", { variant: "secondary" });
        get.disabled = !phone.ready;
        get.addEventListener("click", () => void deliveryApi.fetchKey(ctx.client, phone.deviceId).then(() => refreshPhones(), (e) => say((e as Error).message, "error")));
        row.append(el("p", "cc-hint", "Its key lets Glass seal passwords that only this phone can open."), get);
      } else {
        // Recompute the fingerprint from the key bytes here, so the PC cannot show one key and hand over another.
        const shown = await fingerprint(fromB64(phone.key.publicKey)).catch(() => "");
        const same = shown === phone.key.fingerprint;
        row.append(el("code", "vault-fingerprint", shown || "?"));
        if (!same) row.append(chip("Key and fingerprint differ: do not trust", "danger"));
        if (phone.key.trusted) {
          row.append(chip(phone.key.strongBox ? "Trusted · StrongBox" : "Trusted · secure hardware", "success"));
          const stop = actionButton("Stop trusting", { variant: "ghost" });
          stop.addEventListener("click", () => void deliveryApi.untrust(ctx.client, phone.deviceId).then(() => refreshPhones()));
          row.append(stop);
        } else if (same) {
          row.append(el("p", "cc-hint", "On the phone, open Settings → Vault → Command Center key. Trust it only if the letters match exactly."));
          const match = actionButton("They match, trust this phone", { variant: "primary" });
          match.addEventListener("click", () => void deliveryApi.trust(ctx.client, phone.deviceId, shown).then(() => refreshPhones(), (e) => say((e as Error).message, "error")));
          row.append(match);
        }
      }
      rows.push(row);
    }
    setChildren(phonesBox, el("h2", "card-title", "Phones"), ...(rows.length ? rows : [el("p", "cc-hint", "No phone is connected.")]));
  }

  function phonesCard(): HTMLElement {
    phonesBox = card("cc-card vault-card");
    phonesBox.append(loadingState("Loading phones…"));
    void refreshPhones();
    return phonesBox;
  }

  // ------------------------------------------------------------------ import, backup, settings

  function importCard(): HTMLElement {
    const box = card("cc-card vault-card");
    box.append(el("h2", "card-title", "Import"), el("p", "cc-hint", "From Bitwarden (JSON, not encrypted) or a CSV export (Chrome, Edge, 1Password, Firefox and others). The file is read in this tab and each item is encrypted before it is saved. Delete the export file afterwards."));
    const file = fileInput(".json,.csv,text/csv,application/json", "Export file");
    let parsed: ImportResult | null = null;
    const summary = el("p", "cc-hint");
    const read = actionButton("Read file", { variant: "secondary" });
    const go = actionButton("Import", { variant: "primary" });
    go.disabled = true;
    read.addEventListener("click", async () => {
      const picked = file.files?.[0];
      if (!picked) return say("Pick the export file first.", "error");
      try {
        parsed = parseExport(picked.name, await picked.text());
        summary.textContent = `${parsed.items.length} items ready${parsed.skipped ? `, ${parsed.skipped} skipped (cards, identities and empty rows are never imported)` : ""}.`;
        go.disabled = !parsed.items.length;
      } catch (error) {
        parsed = null;
        say((error as Error).message, "error");
      }
    });
    go.addEventListener("click", async () => {
      if (!parsed || !vk) return;
      go.disabled = true;
      try {
        const records: VaultRecord[] = [];
        for (const entry of parsed.items) records.push(await encryptItem(vk, { id: newItemId(), kind: entry.kind, version: 1 }, entry.fields));
        parsed = null;
        let saved: VaultRecord[] = [];
        for (let i = 0; i < records.length; i += 200) saved = saved.concat((await vaultApi.importMany(ctx.client, records.slice(i, i + 200))).items);
        void vaultApi.audit(ctx.client, "import");
        state = await vaultApi.get(ctx.client);
        items = await openAll(vk, state.items);
        say(`Imported ${saved.length} items. Now delete the export file from this PC.`);
        render();
      } catch (error) {
        say((error as Error).message, "error");
      }
    });
    box.append(labelled("Export file", file), row(read, go), summary);
    return box;
  }

  function settingsCard(): HTMLElement {
    const box = card("cc-card vault-card");
    box.append(el("h2", "card-title", "Backup and keys"));
    const backup = actionButton("Download encrypted backup", { icon: "download" });
    backup.addEventListener("click", async () => {
      const fresh = await vaultApi.get(ctx.client);
      download(`Cyclone-vault-backup-${new Date().toISOString().slice(0, 10)}.json`, backupFile(fresh));
      void vaultApi.audit(ctx.client, "export");
      say("Backup downloaded. It opens only with your passphrase or recovery key.");
    });
    const current = secretInput("Current passphrase or recovery key");
    const next = secretInput("New passphrase");
    const change = actionButton("Change passphrase", { variant: "secondary" });
    const proof = (): Unlocker => {
      const value = String(current.value ?? "");
      return /^[A-Za-z2-7]{4}(-[A-Za-z2-7]{4}){12}$/.test(value.trim()) ? { recoveryKey: value } : { passphrase: value };
    };
    change.addEventListener("click", async () => {
      try {
        const body = await rewrapPassphrase(state!.meta!, proof(), String(next.value ?? ""));
        state = await vaultApi.rewrap(ctx.client, body);
        current.value = "";
        next.value = "";
        say("Passphrase changed. Your items did not need re-encrypting.");
      } catch (error) {
        say((error as Error).message, "error");
      }
    });
    const rotate = actionButton("New recovery key", { variant: "secondary" });
    rotate.addEventListener("click", async () => {
      try {
        const made = await newRecoveryKey(state!.meta!, proof());
        state = await vaultApi.rewrap(ctx.client, made.body);
        current.value = "";
        setChildren(element, recoveryCard(made.recoveryKey, () => render()));
        say("New recovery key made. The old one no longer works.");
      } catch (error) {
        say((error as Error).message, "error");
      }
    });
    box.append(
      row(backup),
      el("p", "cc-hint", `A new passphrase needs at least ${MIN_PASSPHRASE} characters. Prove it's you with the current passphrase or the recovery key.`),
      labelled("Current passphrase or recovery key", current), labelled("New passphrase", next), row(change, rotate),
    );
    return box;
  }

  void load();

  return {
    element,
    destroy() {
      if (vk) void vaultApi.audit(ctx.client, "lock");
      destroyed = true;
      vk = null;
      items = [];
      revealed.clear();
      if (idle) clearTimeout(idle);
      for (const t of timers) clearTimeout(t);
      if (deliveryTimer) clearTimeout(deliveryTimer);
      timers.clear();
    },
  };
}

function secretInput(label: string): HTMLInputElement {
  const input = el("input", "cc-input");
  input.type = "password";
  input.autocomplete = "off";
  input.setAttribute("aria-label", label);
  input.setAttribute("spellcheck", "false");
  return input;
}

function textInput(label: string, value?: string): HTMLInputElement {
  const input = el("input", "cc-input");
  input.setAttribute("aria-label", label);
  input.value = value ?? "";
  return input;
}

function fileInput(accept: string, label: string): HTMLInputElement {
  const input = el("input", "cc-input");
  input.type = "file";
  input.accept = accept;
  input.setAttribute("aria-label", label);
  return input;
}

function labelled(label: string, control: HTMLElement): HTMLElement {
  const wrap = el("label", "cc-field");
  wrap.append(el("span", "cc-field-label", label), control);
  return wrap;
}

function row(...children: HTMLElement[]): HTMLElement {
  const wrap = el("div", "cc-actions");
  wrap.append(...children);
  return wrap;
}

function cellText(value: string): HTMLElement {
  return el("td", undefined, value);
}

function download(name: string, content: string): void {
  try {
    const url = URL.createObjectURL(new Blob([content], { type: "application/octet-stream" }));
    const a = el("a");
    a.href = url;
    a.download = name;
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 5_000);
  } catch {
    /* tests and old browsers: nothing to download into */
  }
}

function recoveryKit(key: string): string {
  return [
    "Cyclone vault recovery kit",
    "",
    `Recovery key: ${key}`,
    `Made: ${new Date().toISOString()}`,
    "",
    "This key opens your Cyclone vault if you forget your passphrase.",
    "Keep it offline (printed, or in a safe place). Anyone with this key and your vault can read it.",
    "Glass does not keep a copy.",
    "",
  ].join("\n");
}
