/**
 * Vault import (plan 33, C1): read another password manager's export in this tab, turn it into item fields, and let
 * Glass encrypt each one before it leaves the tab. The file never goes to the gateway as it is.
 *
 * Supported: Bitwarden's JSON export (unencrypted), and CSV exports with a header row (Chrome, Edge, 1Password,
 * Bitwarden, Firefox, and most others use the same few column names).
 */
import { emptyFields, type ItemFields, type ItemKind } from "./vault.js";

export interface ImportedItem { kind: ItemKind; fields: ItemFields }
export interface ImportResult { items: ImportedItem[]; skipped: number; source: "bitwarden-json" | "csv" }

export function parseExport(name: string, content: string): ImportResult {
  const trimmed = content.replace(/^﻿/, "").trim();
  if (/\.json$/i.test(name) || trimmed.startsWith("{")) return parseBitwarden(trimmed);
  return parseCsv(trimmed);
}

export function parseBitwarden(content: string): ImportResult {
  let data: unknown;
  try {
    data = JSON.parse(content);
  } catch {
    throw new Error("That JSON file could not be read.");
  }
  const root = data as { encrypted?: unknown; items?: unknown };
  if (root.encrypted === true) throw new Error("That Bitwarden export is encrypted. Export as JSON (not encrypted), import it here, then delete the file.");
  if (!Array.isArray(root.items)) throw new Error("That is not a Bitwarden export.");
  const items: ImportedItem[] = [];
  let skipped = 0;
  for (const raw of root.items as Array<Record<string, unknown>>) {
    const login = (raw.login ?? {}) as Record<string, unknown>;
    const uris = Array.isArray(login.uris) ? (login.uris as Array<Record<string, unknown>>) : [];
    const fields: ItemFields = {
      ...emptyFields(),
      label: str(raw.name).slice(0, 120),
      notes: str(raw.notes).slice(0, 4000),
    };
    if (raw.type === 1) {
      fields.username = str(login.username).slice(0, 200);
      fields.secret = str(login.password).slice(0, 1000);
      fields.url = str(uris[0]?.uri).slice(0, 500);
      fields.totp = str(login.totp).slice(0, 200);
      if (!fields.secret && !fields.username && !fields.totp) {
        skipped += 1;
        continue;
      }
      items.push({ kind: "login", fields });
    } else if (raw.type === 2) {
      items.push({ kind: "note", fields });
    } else {
      skipped += 1; // cards and identities stay out of the vault: payment data is never automated
    }
  }
  return { items, skipped, source: "bitwarden-json" };
}

const COLUMNS: Record<keyof Pick<ItemFields, "label" | "url" | "username" | "secret" | "notes" | "totp">, string[]> = {
  label: ["name", "title", "account", "login_name"],
  url: ["url", "login_uri", "website", "uri", "hostname", "web site"],
  username: ["username", "login_username", "user", "email", "login"],
  secret: ["password", "login_password", "pass"],
  notes: ["notes", "note", "extra", "comments"],
  totp: ["totp", "login_totp", "otpauth", "one-time password", "otp"],
};

export function parseCsv(content: string): ImportResult {
  const rows = csvRows(content);
  if (rows.length < 2) throw new Error("That CSV has no rows.");
  const header = rows[0]!.map((h) => h.trim().toLowerCase());
  const index = (names: string[]) => header.findIndex((h) => names.includes(h));
  const at = Object.fromEntries(Object.entries(COLUMNS).map(([k, names]) => [k, index(names)])) as Record<keyof typeof COLUMNS, number>;
  if (at.secret < 0) throw new Error("That CSV has no password column.");
  const items: ImportedItem[] = [];
  let skipped = 0;
  for (const row of rows.slice(1)) {
    const cell = (i: number, max: number) => (i >= 0 ? (row[i] ?? "").slice(0, max) : "");
    const fields: ItemFields = {
      ...emptyFields(),
      label: cell(at.label, 120) || hostOf(cell(at.url, 500)),
      url: cell(at.url, 500),
      username: cell(at.username, 200),
      secret: cell(at.secret, 1000),
      notes: cell(at.notes, 4000),
      totp: cell(at.totp, 200),
    };
    if (!fields.secret && !fields.username) {
      skipped += 1;
      continue;
    }
    items.push({ kind: "login", fields });
  }
  return { items, skipped, source: "csv" };
}

/** RFC 4180: quoted fields, doubled quotes, commas and newlines inside quotes. */
export function csvRows(content: string): string[][] {
  const rows: string[][] = [];
  let row: string[] = [];
  let field = "";
  let quoted = false;
  for (let i = 0; i < content.length; i += 1) {
    const c = content[i]!;
    if (quoted) {
      if (c === '"' && content[i + 1] === '"') {
        field += '"';
        i += 1;
      } else if (c === '"') quoted = false;
      else field += c;
    } else if (c === '"') quoted = true;
    else if (c === ",") {
      row.push(field);
      field = "";
    } else if (c === "\n" || c === "\r") {
      if (c === "\r" && content[i + 1] === "\n") i += 1;
      row.push(field);
      if (row.some((v) => v !== "")) rows.push(row);
      row = [];
      field = "";
    } else field += c;
  }
  row.push(field);
  if (row.some((v) => v !== "")) rows.push(row);
  return rows;
}

function hostOf(url: string): string {
  try {
    return new URL(url).hostname;
  } catch {
    return url.slice(0, 60);
  }
}

function str(value: unknown): string {
  return typeof value === "string" ? value : "";
}
