/**
 * An app's open issues, gathered from what the phone already reports (versions, scenarios, runs, the map). Glass only
 * sorts and words them; every fact and every judgement of a run (its cause of death) comes from the phone.
 */
import type { AppTab } from "../core/router.js";
import type { AppVersions, ScenarioList } from "./knowledge.js";
import { causeLabel, type RunSummary } from "./runs.js";

export type IssueSeverity = "critical" | "warning" | "info";

export type IssueAction =
  | { kind: "tab"; tab: AppTab; label: string }
  | { kind: "map"; rooms: string[]; label: string }
  | { kind: "run"; runId: string; label: string };

export interface AppIssue {
  id: string;
  severity: IssueSeverity;
  title: string;
  detail: string;
  action: IssueAction;
}

export interface IssueInputs {
  placeId: string;
  versions: AppVersions | null;
  scenarios: ScenarioList | null;
  runs: RunSummary[] | null;
  /** Rooms on the map with their confidence (0..1). */
  rooms: Array<{ screenId: string; confidence: number }> | null;
}

const RANK: Record<IssueSeverity, number> = { critical: 0, warning: 1, info: 2 };
const LOW_CONFIDENCE = 0.5;

export function appIssues(input: IssueInputs): AppIssue[] {
  const issues: AppIssue[] = [];
  const v = input.versions;
  if (v?.needsRemap) {
    issues.push({
      id: "needs-remap",
      severity: "warning",
      title: "The app was updated since it was mapped",
      detail: "Doors learned on the old version may not work any more. Remap to learn the installed version.",
      action: { kind: "tab", tab: "versions", label: "See versions" },
    });
  }
  if (v && v.staleDoorCount > 0) {
    const rooms = [...new Set(v.staleDoors.flatMap((door) => [door.fromScreenId, door.toScreenId]))].slice(0, 12);
    issues.push({
      id: "stale-doors",
      severity: "warning",
      title: `${v.staleDoorCount} ${v.staleDoorCount === 1 ? "door was" : "doors were"} last confirmed on an older version`,
      detail: "Cyclone will still try them, but they may lead somewhere else now.",
      action: rooms.length ? { kind: "map", rooms, label: "Show them on the map" } : { kind: "tab", tab: "versions", label: "See versions" },
    });
  }
  const failingDoors = v?.versions.find((row) => row.installed)?.failingDoors ?? 0;
  if (failingDoors > 0) {
    issues.push({
      id: "failing-doors",
      severity: "warning",
      title: `${failingDoors} ${failingDoors === 1 ? "door fails" : "doors fail"} on the installed version`,
      detail: "The last try of these doors failed. Remap the rooms they leave from or teach the way again.",
      action: { kind: "tab", tab: "versions", label: "See versions" },
    });
  }
  for (const scenario of input.scenarios?.scenarios ?? []) {
    if (scenario.health !== "critical" && scenario.health !== "warning") continue;
    issues.push({
      id: `scenario:${scenario.scenarioId}`,
      severity: scenario.health === "critical" ? "critical" : "warning",
      title: `${scenario.title} is ${scenario.health === "critical" ? "failing" : "unreliable"}`,
      detail: scenario.health === "critical" ? "The last two runs that went here did not finish." : "Runs that went here sometimes fail.",
      action: { kind: "map", rooms: scenario.route, label: "Show the route" },
    });
  }
  const failed = (input.runs ?? [])
    .filter((run) => run.status === "failed" && run.expected !== true && run.places.some((place) => place.placeId === input.placeId))
    .sort((a, b) => b.startedAt - a.startedAt);
  const byCause = new Map<string, RunSummary[]>();
  for (const run of failed.slice(0, 30)) {
    const kind = run.cause?.kind ?? "unknown";
    byCause.set(kind, [...(byCause.get(kind) ?? []), run]);
  }
  for (const [kind, runs] of byCause) {
    issues.push({
      id: `runs:${kind}`,
      severity: runs.length >= 2 ? "critical" : "warning",
      title: `${runs.length} failed ${runs.length === 1 ? "run" : "runs"}: ${causeLabel(kind)}`,
      detail: runs[0]!.cause?.fix || "Open the latest one to see the step where it broke.",
      action: { kind: "run", runId: runs[0]!.runId, label: "Open the latest" },
    });
  }
  const uncertain = (input.rooms ?? []).filter((room) => Number.isFinite(room.confidence) && room.confidence < LOW_CONFIDENCE);
  if (uncertain.length) {
    issues.push({
      id: "uncertain-rooms",
      severity: "info",
      title: `${uncertain.length} ${uncertain.length === 1 ? "room is" : "rooms are"} uncertain`,
      detail: "Cyclone saw these rooms only briefly. Another mapping pass makes them reliable.",
      action: { kind: "map", rooms: uncertain.slice(0, 12).map((room) => room.screenId), label: "Show them on the map" },
    });
  }
  return issues.sort((a, b) => RANK[a.severity] - RANK[b.severity]);
}
