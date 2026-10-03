/**
 * What Cyclone knows beyond maps (`knowledge.get`): vault slots as set / not set (never values), taught skills and
 * automations, Atlas totals. The phone computes it; Glass only lays it out.
 */
import type { GatewayClient } from "./gateway.js";

export interface VaultSlot {
  placeId: string;
  persona: "live" | "mapping";
  slot: string;
  set: boolean;
  updatedAt: number | null;
}

export interface KnowledgeSummary {
  vault: { slotCount: number; setCount: number; slots: VaultSlot[] };
  skills: Array<{ id: string; name: string; steps: number; enabled: boolean; version: number }>;
  automations: Array<{ id: string; name: string; trigger: string; steps: number; enabled: boolean }>;
  atlas: { places: number; rooms: number; doors: number };
  /** The never-pay list: doors and rooms the phone marked dangerous, per app. `null` from phones before alpha.15. */
  guarded: GuardedRow[] | null;
}

export type GuardedDanger = "payment" | "send-public" | "delete-account" | "logout-all" | "permission";

export interface GuardedRow {
  placeId: string;
  label: string;
  persona: "live" | "mapping";
  danger: GuardedDanger;
  doors: number;
  rooms: number;
  /** Structural room ids to light up on the map (phone alpha.17+); empty on older phones. */
  roomIds: string[];
}

const ROOM_ID = /^screen:[a-z_]{1,40}:[0-9a-f]{8,64}$/;

const DANGERS: Record<GuardedDanger, string> = {
  payment: "Pay or buy",
  "send-public": "Send or post",
  "delete-account": "Delete",
  "logout-all": "Sign out everywhere",
  permission: "Grant a permission",
};

export function dangerLabel(danger: GuardedDanger): string {
  return DANGERS[danger];
}

export async function getKnowledge(client: GatewayClient, deviceId: string, signal?: AbortSignal): Promise<KnowledgeSummary> {
  return parseKnowledge(await client.get<unknown>(`/v1/devices/${encodeURIComponent(deviceId)}/knowledge`, signal));
}

export function parseKnowledge(raw: unknown): KnowledgeSummary {
  const r = record(raw);
  const vault = record(r.vault);
  const atlas = record(r.atlas);
  return {
    vault: {
      slotCount: num(vault.slotCount),
      setCount: num(vault.setCount),
      slots: list(vault.slots)
        .filter((s) => typeof s.placeId === "string" && typeof s.slot === "string")
        .map((s) => ({
          placeId: s.placeId as string,
          persona: s.persona === "mapping" ? "mapping" : "live",
          slot: s.slot as string,
          set: s.set === true,
          updatedAt: typeof s.updatedAt === "number" ? s.updatedAt : null,
        })),
    },
    skills: list(r.skills)
      .filter((s) => typeof s.id === "string")
      .map((s) => ({ id: s.id as string, name: str(s.name) || "Untitled skill", steps: num(s.steps), enabled: s.enabled === true, version: num(s.version) })),
    automations: list(r.automations)
      .filter((s) => typeof s.id === "string")
      .map((s) => ({ id: s.id as string, name: str(s.name) || "Untitled automation", trigger: str(s.trigger), steps: num(s.steps), enabled: s.enabled === true })),
    atlas: { places: num(atlas.places), rooms: num(atlas.rooms), doors: num(atlas.doors) },
    guarded: Array.isArray(r.guarded)
      ? list(r.guarded)
          .filter((g) => typeof g.placeId === "string" && typeof g.danger === "string" && g.danger in DANGERS)
          .map((g) => ({
            placeId: g.placeId as string,
            label: str(g.label),
            persona: g.persona === "live" ? "live" : "mapping",
            danger: g.danger as GuardedDanger,
            doors: num(g.doors),
            rooms: num(g.rooms),
            roomIds: Array.isArray(g.roomIds) ? g.roomIds.filter((id): id is string => typeof id === "string" && ROOM_ID.test(id)).slice(0, 10) : [],
          }))
      : null,
  };
}

function list(value: unknown): Array<Record<string, unknown>> {
  return Array.isArray(value) ? value.map(record) : [];
}

function record(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" && !Array.isArray(value) ? (value as Record<string, unknown>) : {};
}

function str(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

function num(value: unknown): number {
  return typeof value === "number" && Number.isFinite(value) ? value : 0;
}
