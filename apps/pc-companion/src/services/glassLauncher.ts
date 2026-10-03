/**
 * Open Cyclone Glass (the local browser dashboard, `apps/glass`) from Cyclone One.
 *
 * One asks its own loopback gateway for a single-use launch code, then the Rust `open_glass` command opens the
 * default browser on that link. The bearer never leaves this process; the browser only ever sees the one-time code.
 */
export interface GlassGateway {
  httpBase: string;
  getBearer: () => string;
}

export type OpenInBrowser = (path: string) => Promise<string>;

const LAUNCH_PATH = /^\/glass\/#code=[A-Za-z0-9_-]{16,128}$/;

export async function openCycloneGlass(
  gateway: GlassGateway | undefined,
  openInBrowser: OpenInBrowser,
  fetchImpl: typeof fetch = (input, init) => globalThis.fetch(input, init),
): Promise<string> {
  if (!gateway) throw new Error("Cyclone's local gateway is not running yet.");
  const response = await fetchImpl(`${gateway.httpBase.replace(/\/$/, "")}/v1/glass/launch-code`, {
    method: "POST",
    cache: "no-store",
    headers: { Authorization: `Bearer ${gateway.getBearer()}`, "Content-Type": "application/json" },
    body: "{}",
  });
  if (!response.ok) {
    throw new Error(response.status === 404 ? "This Cyclone runtime predates Glass. Update Cyclone One." : `Glass could not start (${response.status}).`);
  }
  const body = (await response.json().catch(() => null)) as { path?: unknown; bundle?: unknown } | null;
  const path = typeof body?.path === "string" ? body.path : "";
  if (!LAUNCH_PATH.test(path)) throw new Error("The gateway returned an unexpected Glass link.");
  if (body?.bundle === false) throw new Error("Cyclone Glass is not included in this build.");
  return openInBrowser(path);
}
