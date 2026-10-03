/**
 * Same-origin client for the local Cyclone gateway. Glass has no other backend.
 * Errors are normalised from the gateway's `{detail: {code, message, retryable}}` shape.
 */
export class GatewayError extends Error {
  readonly code: string;
  readonly status: number;
  readonly retryable: boolean;

  constructor(code: string, message: string, status: number, retryable = false) {
    super(message);
    this.name = "GatewayError";
    this.code = code;
    this.status = status;
    this.retryable = retryable;
  }

  get sessionExpired(): boolean {
    return this.code === "SESSION_EXPIRED";
  }
}

export interface GatewayClientOptions {
  token: string;
  fetch?: typeof fetch;
  /** Empty for same-origin (the served bundle). */
  baseUrl?: string;
  /** Called once when the gateway rejects the bearer (restarted gateway, rotated token). */
  onSessionExpired?: () => void;
}

export class GatewayClient {
  readonly baseUrl: string;
  private readonly token: string;
  private readonly fetchImpl: typeof fetch;
  private readonly onSessionExpired?: () => void;
  private expiredNotified = false;

  constructor(options: GatewayClientOptions) {
    this.token = options.token;
    this.baseUrl = (options.baseUrl ?? "").replace(/\/$/, "");
    this.fetchImpl = options.fetch ?? ((input, init) => globalThis.fetch(input, init));
    this.onSessionExpired = options.onSessionExpired;
  }

  bearer(): string {
    return this.token;
  }

  /** `fetch` with the bearer attached; for clients (atlas, mapping) that parse responses themselves. */
  authorizedFetch: typeof fetch = (input, init = {}) => {
    const headers = new Headers(init.headers);
    headers.set("Authorization", `Bearer ${this.token}`);
    return this.fetchImpl(input, { ...init, headers, cache: "no-store", credentials: "omit" });
  };

  get<T>(path: string, signal?: AbortSignal): Promise<T> {
    return this.request<T>("GET", path, undefined, signal);
  }

  post<T>(path: string, body?: unknown, signal?: AbortSignal): Promise<T> {
    return this.request<T>("POST", path, body ?? {}, signal);
  }

  put<T>(path: string, body: unknown, signal?: AbortSignal): Promise<T> {
    return this.request<T>("PUT", path, body, signal);
  }

  /** Offer the gateway's selected protocol as well as the bearer used to authenticate the handshake. */
  socket(path: string, origin: string): { url: string; protocols: string[] } {
    const base = this.baseUrl || origin;
    const url = base.replace(/^http/, "ws") + path;
    return { url, protocols: ["cyclone-v1", `cyclone-token.${this.token}`] };
  }

  private async request<T>(method: string, path: string, body: unknown, signal?: AbortSignal): Promise<T> {
    let response: Response;
    try {
      response = await this.authorizedFetch(this.baseUrl + path, {
        method,
        signal,
        headers: body === undefined ? { Accept: "application/json" } : { Accept: "application/json", "Content-Type": "application/json" },
        body: body === undefined ? undefined : JSON.stringify(body),
      });
    } catch (error) {
      if ((error as { name?: string })?.name === "AbortError") throw error;
      throw new GatewayError("GATEWAY_UNREACHABLE", "Cyclone's local gateway is not running on this PC.", 0, true);
    }
    const payload: unknown = await response.json().catch(() => null);
    if (response.ok) return payload as T;
    throw this.toError(response.status, payload);
  }

  private toError(status: number, payload: unknown): GatewayError {
    const detail = (payload as { detail?: unknown } | null)?.detail;
    const structured = Boolean(detail) && typeof detail === "object" && !Array.isArray(detail);
    // Only a rejected bearer ends the Glass session. The gateway also answers 401/403 with a structured
    // runtime code (PAIRING_REQUIRED, AUTH_REJECTED from the phone); those are about the phone, not this tab.
    if ((status === 401 || status === 403) && !structured) {
      if (!this.expiredNotified) {
        this.expiredNotified = true;
        this.onSessionExpired?.();
      }
      return new GatewayError("SESSION_EXPIRED", "This Glass session has ended. Reopen Glass from the launcher.", status);
    }
    if (structured) {
      const record = detail as { code?: unknown; message?: unknown; retryable?: unknown };
      const code = typeof record.code === "string" && record.code ? record.code : `HTTP_${status}`;
      const message = typeof record.message === "string" && record.message ? record.message : `Gateway error ${status}`;
      return new GatewayError(code, message, status, record.retryable === true);
    }
    if (typeof detail === "string" && detail) return new GatewayError(`HTTP_${status}`, detail, status);
    return new GatewayError(`HTTP_${status}`, `Gateway error ${status}`, status);
  }
}
