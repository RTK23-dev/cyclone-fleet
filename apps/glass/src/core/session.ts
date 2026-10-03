/**
 * Glass session bootstrap.
 *
 * The launcher (`cyclone-device-gateway glass`) opens `/glass/#code=<one-time code>`. The page exchanges the
 * code for the gateway bearer once, keeps it for this tab only (memory + sessionStorage, never localStorage)
 * and removes the code from the address bar. A reload in the same tab keeps working; a new tab needs the launcher.
 */
export const SESSION_STORAGE_KEY = "cyclone.glass.session.v1";
const CODE_PATTERN = /^[A-Za-z0-9_-]{16,128}$/;

export interface TabStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

export interface SessionEnv {
  hash: string;
  storage: TabStorage | null;
  fetch: typeof fetch;
  /** Replace the address-bar hash without adding history (drops the launch code). */
  replaceHash(hash: string): void;
}

export type SessionResult =
  | { state: "ready"; token: string }
  | { state: "needs-launch"; reason: "no-session" | "code-rejected" | "gateway-unreachable"; detail?: string };

export function readLaunchCode(hash: string): string | null {
  const params = new URLSearchParams(hash.replace(/^#/, "").replace(/^\/[^?&]*[?&]?/, ""));
  const code = params.get("code");
  return code && CODE_PATTERN.test(code) ? code : null;
}

export async function establishSession(env: SessionEnv): Promise<SessionResult> {
  const code = readLaunchCode(env.hash);
  if (code) {
    env.replaceHash("#/");
    let response: Response;
    try {
      response = await env.fetch("/v1/glass/session", {
        method: "POST",
        headers: { "Content-Type": "application/json", Accept: "application/json" },
        body: JSON.stringify({ code }),
        cache: "no-store",
        credentials: "omit",
      });
    } catch (error) {
      return { state: "needs-launch", reason: "gateway-unreachable", detail: errorText(error) };
    }
    if (!response.ok) return { state: "needs-launch", reason: "code-rejected" };
    const body = (await response.json().catch(() => null)) as { token?: unknown } | null;
    const token = typeof body?.token === "string" ? body.token : "";
    if (!token) return { state: "needs-launch", reason: "code-rejected" };
    safeStorage(() => env.storage?.setItem(SESSION_STORAGE_KEY, token));
    return { state: "ready", token };
  }
  const stored = safeStorage(() => env.storage?.getItem(SESSION_STORAGE_KEY) ?? null);
  if (stored) return { state: "ready", token: stored };
  return { state: "needs-launch", reason: "no-session" };
}

export function forgetSession(storage: TabStorage | null): void {
  safeStorage(() => storage?.removeItem(SESSION_STORAGE_KEY));
}

function safeStorage<T>(fn: () => T): T | null {
  try {
    return fn();
  } catch {
    return null;
  }
}

function errorText(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
