/**
 * Accounts, rebuilt (plan 43 T5 + T6): phones, their apps, the accounts in each app, and the sign-ups Cyclone mapped.
 * The phone walks an app's sign-up once and keeps a Sign-up Map (pages, field labels and kinds, format hints, choices,
 * the steps only a person can do). A map is a template, never a value. The gateway turns it into a table whose rows
 * are the next accounts to create. This module holds the types, parsers, API and the pure helpers the view uses.
 */
import type { GatewayClient } from "./gateway.js";
import type { CcAccount, CcTask, OwnerBasis } from "./command.js";
import type { PhoneApp } from "./apps.js";

export type SignupKind =
  | "text" | "first_name" | "last_name" | "full_name" | "email" | "phone" | "username" | "password" | "birthday" | "date"
  | "gender" | "choice" | "checkbox" | "number" | "photo";
export type SignupCheck = "email_code" | "sms_code" | "captcha" | "selfie" | "id_document" | "phone_call" | "other";

const KINDS: readonly SignupKind[] = ["text", "first_name", "last_name", "full_name", "email", "phone", "username", "password", "birthday",
  "date", "gender", "choice", "checkbox", "number", "photo"];
const CHECKS: readonly SignupCheck[] = ["email_code", "sms_code", "captcha", "selfie", "id_document", "phone_call", "other"];

export const KIND_LABEL: Record<SignupKind, string> = {
  text: "Text", first_name: "First name", last_name: "Last name", full_name: "Full name", email: "Email", phone: "Phone number",
  username: "Username", password: "Password (vault)", birthday: "Birthday", date: "Date", gender: "Gender", choice: "Choice",
  checkbox: "Checkbox", number: "Number", photo: "Photo",
};
export const CHECK_LABEL: Record<SignupCheck, string> = {
  email_code: "Email code", sms_code: "SMS code", captcha: "CAPTCHA", selfie: "Selfie", id_document: "ID document",
  phone_call: "Phone call", other: "A person's step",
};
export const BASIS_CHOICES: ReadonlyArray<{ id: OwnerBasis; label: string }> = [
  { id: "mine", label: "Mine" }, { id: "company", label: "My company's" }, { id: "client", label: "A client's I manage" },
];

export interface SignupField { key: string; label: string; kind: SignupKind; required: boolean; hint: string; choices: string[] }
export interface SignupPage { index: number; title: string; continueLabel: string; check: SignupCheck | null; fields: SignupField[] }
export interface SignupMap {
  packageName: string;
  app: string;
  appVersion: string;
  mappedAt: number;
  finalLabel: string | null;
  complete: boolean;
  pages: SignupPage[];
  fetchedAt: number;
  /** The sign-up table made from this map, while it exists. */
  tableId: string | null;
}
export interface SignupMaps { deviceId: string; maps: SignupMap[]; fresh: boolean; note: string | null }

/** Where an app stands for Account Setup on one phone. */
export type SignupState = "none" | "mapping" | "partial" | "mapped";
export const SIGNUP_STATE_LABEL: Record<SignupState, string> = { none: "Sign-up not mapped", mapping: "Mapping…", partial: "Partly mapped", mapped: "Sign-up mapped" };
export const SIGNUP_STATE_TONE: Record<SignupState, "neutral" | "accent" | "warning" | "success"> = { none: "neutral", mapping: "accent", partial: "warning", mapped: "success" };

export const RECIPE = "signup_map:";

export function parseSignupMaps(raw: unknown): SignupMaps {
  const r = obj(raw);
  return {
    deviceId: str(r.deviceId),
    maps: list(r.maps).map(parseMap).filter((m): m is SignupMap => m !== null),
    fresh: r.fresh !== false,
    note: typeof r.note === "string" && r.note ? r.note : null,
  };
}

export function parseMap(raw: unknown): SignupMap | null {
  const r = obj(raw);
  const packageName = str(r.package);
  if (!packageName) return null;
  const pages = list(r.pages).map((p, i): SignupPage => {
    const q = obj(p);
    return {
      index: typeof q.index === "number" ? q.index : i + 1,
      title: str(q.title),
      continueLabel: str(q.continue),
      check: CHECKS.includes(q.check as SignupCheck) ? (q.check as SignupCheck) : null,
      fields: list(q.fields).map((f): SignupField => {
        const x = obj(f);
        return {
          key: str(x.key), label: str(x.label), kind: KINDS.includes(x.kind as SignupKind) ? (x.kind as SignupKind) : "text",
          required: x.required !== false, hint: str(x.hint), choices: list(x.choices).filter((c): c is string => typeof c === "string"),
        };
      }),
    };
  });
  return {
    packageName, app: str(r.app) || packageName, appVersion: str(r.appVersion), mappedAt: num(r.mappedAt),
    finalLabel: typeof r.finalLabel === "string" && r.finalLabel ? r.finalLabel : null, complete: r.complete === true, pages,
    fetchedAt: num(r.fetchedAt), tableId: typeof r.tableId === "string" && r.tableId ? r.tableId : null,
  };
}

export const signupApi = {
  maps: async (client: GatewayClient, deviceId: string, signal?: AbortSignal) =>
    parseSignupMaps(await client.get(`/v1/cc/signup/maps?deviceId=${encodeURIComponent(deviceId)}`, signal)),
  map: (client: GatewayClient, body: { deviceId: string; package: string; app: string; ownerBasis: OwnerBasis }) =>
    client.post("/v1/cc/signup/map", body),
  forget: (client: GatewayClient, deviceId: string, packageName: string) => client.post("/v1/cc/signup/forget", { deviceId, package: packageName }),
  /** T7 step 1: the Ready rows, each with its Cyclone account, so Glass can put a password for it in the vault. */
  prepare: async (client: GatewayClient, tableId: string, rowIds?: string[]) =>
    parsePrepared(await client.post("/v1/cc/signup/prepare", rowIds ? { tableId, rowIds } : { tableId })),
  /** T7 step 2: one task per row; pressing Create accounts is the approval of each account's final create. */
  create: async (client: GatewayClient, tableId: string, rows: Array<{ rowId: string; accountId: string; vaultItemId: string | null }>) => {
    const r = obj(await client.post("/v1/cc/signup/create", { tableId, rows }));
    return {
      started: list(r.started).map((x) => ({ rowId: str(obj(x).rowId), taskId: str(obj(x).taskId) })),
      errors: list(r.errors).map((x) => ({ rowId: str(obj(x).rowId), error: str(obj(x).error) })),
    };
  },
  pause: (client: GatewayClient, tableId: string, rowIds?: string[]) => client.post("/v1/cc/signup/pause", rowIds ? { tableId, rowIds } : { tableId }),
  cancel: (client: GatewayClient, tableId: string, rowIds?: string[]) => client.post("/v1/cc/signup/cancel", rowIds ? { tableId, rowIds } : { tableId }),
  table: async (client: GatewayClient, deviceId: string, packageName: string) => {
    const r = obj(await client.post("/v1/cc/signup/table", { deviceId, package: packageName }));
    return { id: str(r.id), title: str(r.title) };
  },
};

export interface PreparedRow {
  rowId: string;
  title: string;
  accountId: string | null;
  vaultItemId: string | null;
  username: string;
  deviceId: string;
  error: string | null;
}
export interface Prepared { tableId: string; app: string; packageName: string; rows: PreparedRow[] }

export function parsePrepared(raw: unknown): Prepared {
  const r = obj(raw);
  return {
    tableId: str(r.tableId), app: str(r.app), packageName: str(r.package),
    rows: list(r.rows).map((x): PreparedRow => {
      const q = obj(x);
      return {
        rowId: str(q.rowId), title: str(q.title) || "Account", accountId: str(q.accountId) || null, vaultItemId: str(q.vaultItemId) || null,
        username: str(q.username), deviceId: str(q.deviceId), error: typeof q.error === "string" && q.error ? q.error : null,
      };
    }),
  };
}

/** "7 pages · 6 fields · Email code" for an app row. */
export function mapSummary(map: SignupMap): string {
  const fields = map.pages.reduce((n, p) => n + p.fields.length, 0);
  const checks = [...new Set(map.pages.flatMap((p) => (p.check ? [CHECK_LABEL[p.check]] : [])))];
  return [`${map.pages.length} ${map.pages.length === 1 ? "page" : "pages"}`, `${fields} ${fields === 1 ? "field" : "fields"}`, ...checks].join(" · ");
}

/** The columns a sign-up table gets from the map: what the owner fills per account (never passwords, codes or photos). */
export function tableColumns(map: SignupMap): string[] {
  const out: string[] = [];
  for (const page of map.pages) {
    if (page.check) continue;
    for (const field of page.fields) if (field.kind !== "password" && field.kind !== "photo") out.push(field.label);
  }
  return out;
}

/** The mapping task running (or waiting) for this app on this phone, if any. */
export function mappingTask(tasks: CcTask[], deviceId: string, packageName: string): CcTask | null {
  return tasks.find((t) => t.recipe === RECIPE + packageName && (t.deviceId === deviceId || t.run?.deviceId === deviceId)
    && ["scheduled", "waiting_device", "running", "needs_you"].includes(t.status)) ?? null;
}

/** The latest finished mapping task for this app on this phone (shown when there is still no map), if any. */
export function lastMappingTry(tasks: CcTask[], deviceId: string, packageName: string): CcTask | null {
  return tasks.filter((t) => t.recipe === RECIPE + packageName && (t.deviceId === deviceId || t.run?.deviceId === deviceId)
    && (t.status === "failed" || t.status === "cancelled" || t.status === "succeeded")).sort((a, b) => b.createdAt - a.createdAt)[0] ?? null;
}

/** Why a mapping task is where it is, in the owner's words. */
export function mappingDetail(task: CcTask): string {
  const run = task.run?.summary || "";
  switch (task.status) {
    case "scheduled": return "Waiting to start on the phone.";
    case "waiting_device": return task.cause || "Waiting for the phone to be ready.";
    case "running": return run ? `The phone is on it: ${run}` : "The phone is walking the sign-up now.";
    case "needs_you": return "The phone is waiting for you: answer in Inbox or on the phone.";
    default: return task.cause || run || "";
  }
}

export function signupState(map: SignupMap | undefined, task: CcTask | null): SignupState {
  if (task) return "mapping";
  if (!map) return "none";
  return map.complete ? "mapped" : "partial";
}

/** The owner's accounts in an app that this phone may use. */
export function accountsFor(accounts: CcAccount[], packageName: string, deviceId: string): CcAccount[] {
  return accounts.filter((a) => a.service === packageName && (!a.allowedDevices.length || a.allowedDevices.includes(deviceId)));
}

export interface AccountAppRow {
  app: PhoneApp;
  packageName: string;
  accounts: CcAccount[];
  map: SignupMap | undefined;
  task: CcTask | null;
  state: SignupState;
}

/**
 * The phone's installed apps for the Accounts screen: apps with accounts or a sign-up first, then the rest by name.
 * Web places and apps no longer installed stay out; a query matches the name or the package.
 */
export function accountAppRows(apps: PhoneApp[], maps: SignupMap[], accounts: CcAccount[], tasks: CcTask[], deviceId: string, query = ""): AccountAppRow[] {
  const q = query.trim().toLowerCase();
  const byPackage = new Map(maps.map((m) => [m.packageName, m]));
  const rows: AccountAppRow[] = [];
  for (const app of apps) {
    if (app.kind !== "package" || !app.packageName || app.installed === false) continue;
    if (q && !`${app.label} ${app.packageName}`.toLowerCase().includes(q)) continue;
    const map = byPackage.get(app.packageName);
    const task = mappingTask(tasks, deviceId, app.packageName);
    rows.push({ app, packageName: app.packageName, accounts: accountsFor(accounts, app.packageName, deviceId), map, task, state: signupState(map, task) });
  }
  const weight = (r: AccountAppRow) => (r.accounts.length ? 2 : 0) + (r.state !== "none" ? 1 : 0);
  return rows.sort((a, b) => weight(b) - weight(a) || a.app.label.localeCompare(b.app.label, undefined, { sensitivity: "base" }));
}

function obj(raw: unknown): Record<string, unknown> {
  return raw && typeof raw === "object" && !Array.isArray(raw) ? (raw as Record<string, unknown>) : {};
}
function list(raw: unknown): unknown[] {
  return Array.isArray(raw) ? raw : [];
}
function str(raw: unknown): string {
  return typeof raw === "string" ? raw.trim() : "";
}
function num(raw: unknown): number {
  return typeof raw === "number" && Number.isFinite(raw) ? raw : 0;
}
