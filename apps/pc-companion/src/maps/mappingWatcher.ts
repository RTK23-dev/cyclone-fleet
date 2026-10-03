/**
 * Glass follows one phone mapping job. The phone walks; Glass polls `mapping.status` for the cursor
 * and `atlas.diff` for new rooms/doors, and asks the page to refresh the board when the atlas moved.
 *
 * It also attaches to a pass that was started on the phone (Settings → App Maps), so the board
 * animates no matter where the operator pressed Start.
 */

import {
  MAPPING_TERMINAL_STATES,
  type AtlasDiffView,
  type MappingJobView,
} from "../services/atlasClient.js";

export interface MappingOps {
  atlasDiff(placeId: string, persona: "mapping", since: string | null): Promise<AtlasDiffView>;
  mappingStart(placeId: string): Promise<MappingJobView>;
  mappingResume(mappingJobId: string): Promise<MappingJobView>;
  mappingPause(mappingJobId: string): Promise<MappingJobView>;
  mappingStop(mappingJobId: string): Promise<MappingJobView>;
  mappingStatus(mappingJobId?: string | null): Promise<MappingJobView>;
}

export interface MappingWatcherOptions {
  ops: MappingOps;
  /** Every status read, including the terminal one. */
  onJob(job: MappingJobView): void;
  /** The mapping atlas for this place changed (or must be resynced); refetch and repaint. */
  onAtlasChanged(placeId: string): void;
  onError(error: unknown): void;
  intervalMs?: number;
  setTimer?: (fn: () => void, ms: number) => unknown;
  clearTimer?: (handle: unknown) => void;
}

export interface MappingWatcher {
  /** Start a new pass on this place and follow it. */
  start(placeId: string): Promise<void>;
  /** Follow whatever job currently owns the foreground plane, if any. */
  attach(): Promise<void>;
  pause(): Promise<void>;
  resume(): Promise<void>;
  stop(): Promise<void>;
  /** One poll now (tests and manual refresh). */
  tick(): Promise<void>;
  current(): MappingJobView | null;
  dispose(): void;
}

export function isActiveMapping(job: MappingJobView | null): boolean {
  return job != null && !MAPPING_TERMINAL_STATES.has(job.state);
}

export function createMappingWatcher(options: MappingWatcherOptions): MappingWatcher {
  const intervalMs = options.intervalMs ?? 1_000;
  const setTimer = options.setTimer ?? ((fn: () => void, ms: number) => setTimeout(fn, ms));
  const clearTimer = options.clearTimer ?? ((handle: unknown) => clearTimeout(handle as ReturnType<typeof setTimeout>));

  let job: MappingJobView | null = null;
  let placeId: string | null = null;
  let cursor: string | null = null;
  let timer: unknown = null;
  let disposed = false;
  let polling = false;

  const schedule = (): void => {
    if (disposed || timer != null) return;
    if (!isActiveMapping(job)) return;
    timer = setTimer(() => {
      timer = null;
      void tick();
    }, intervalMs);
  };

  const follow = async (next: MappingJobView): Promise<void> => {
    job = next;
    options.onJob(next);
    if (next.placeId && next.placeId !== placeId) {
      placeId = next.placeId;
      cursor = null;
    }
    if (placeId && cursor == null) {
      // Bootstrap a phone-issued cursor; the page's refresh supplies the truth up to now.
      cursor = (await options.ops.atlasDiff(placeId, "mapping", null)).cursor;
      options.onAtlasChanged(placeId);
    }
    schedule();
  };

  async function tick(): Promise<void> {
    if (disposed || polling || !job?.mappingJobId) return;
    polling = true;
    try {
      const next = await options.ops.mappingStatus(job.mappingJobId);
      if (disposed) return;
      job = next;
      options.onJob(next);
      if (placeId) {
        const diff = await options.ops.atlasDiff(placeId, "mapping", cursor);
        if (disposed) return;
        cursor = diff.cursor;
        if (diff.resyncRequired || diff.changes.length > 0) options.onAtlasChanged(placeId);
      }
    } catch (error) {
      options.onError(error);
    } finally {
      polling = false;
      schedule();
    }
  }

  return {
    async start(target: string): Promise<void> {
      placeId = target;
      // Take the cursor before the phone can write, so no first room is missed.
      cursor = (await options.ops.atlasDiff(target, "mapping", null)).cursor;
      const started = await options.ops.mappingStart(target);
      await follow(started);
    },
    async attach(): Promise<void> {
      const found = await options.ops.mappingStatus(null);
      if (isActiveMapping(found)) await follow(found);
      else {
        job = found;
        options.onJob(found);
      }
    },
    async pause(): Promise<void> {
      if (!job?.mappingJobId || !isActiveMapping(job)) return;
      await follow(await options.ops.mappingPause(job.mappingJobId));
    },
    async resume(): Promise<void> {
      if (!job?.mappingJobId || !isActiveMapping(job) || job.state === "running") return;
      await follow(await options.ops.mappingResume(job.mappingJobId));
    },
    async stop(): Promise<void> {
      if (!job?.mappingJobId || !isActiveMapping(job)) return;
      const stopped = await options.ops.mappingStop(job.mappingJobId);
      job = stopped;
      options.onJob(stopped);
      if (placeId) options.onAtlasChanged(placeId);
    },
    tick,
    current: () => job,
    dispose(): void {
      disposed = true;
      if (timer != null) clearTimer(timer);
      timer = null;
    },
  };
}

/** One line for the Maps bar. */
export function mappingStatusLine(job: MappingJobView | null): string {
  if (!job || job.state === "idle") return "";
  const rooms = `${job.newScreens} new ${job.newScreens === 1 ? "room" : "rooms"}`;
  switch (job.state) {
    case "running":
      return `Mapping on the phone · ${rooms}`;
    case "paused":
      return `Mapping paused · ${rooms}`;
    case "needs-secret":
      return "Waiting for a password on the phone";
    case "human-control":
      return `You have the phone · mapping paused · ${rooms}`;
    case "completed":
      return `Mapping done · ${job.atlasStatus === "mapped" ? "every reachable door walked" : "some doors left dark"}`;
    case "stopped":
      return `Mapping stopped · ${rooms}`;
    case "failed":
      return `Mapping stopped: ${job.failureCode ?? "error"}`;
    default:
      return "";
  }
}
