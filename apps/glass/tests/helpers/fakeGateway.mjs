/** A fake local gateway for page tests: routes by method + path, records calls, answers JSON. */
export const READY_DEVICE = {
  deviceId: "d1",
  name: "Pixel 8",
  model: "Pixel 8",
  state: "READY",
  paired: true,
  planes: { aiTrust: "TRUSTED" },
  mobileVersion: "5.0.0-alpha.7.dev1",
};

export function json(body, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

export function fakeGateway(routes) {
  const calls = [];
  const fetch = async (input, init = {}) => {
    const url = new URL(String(input), "http://127.0.0.1:8765");
    const method = (init.method ?? "GET").toUpperCase();
    const body = init.body ? JSON.parse(init.body) : undefined;
    calls.push({ method, path: url.pathname, query: Object.fromEntries(url.searchParams), body, auth: new Headers(init.headers).get("Authorization") });
    for (const [pattern, handler] of Object.entries(routes)) {
      const [m, p] = pattern.split(" ");
      if (m === method && p === url.pathname) {
        const result = await handler({ query: Object.fromEntries(url.searchParams), body, calls });
        return result instanceof Response ? result : json(result);
      }
    }
    return json({ detail: { code: "NOT_FOUND", message: `no fake route ${method} ${url.pathname}` } }, 404);
  };
  return { fetch, calls };
}

export async function flush(times = 8) {
  for (let i = 0; i < times; i += 1) await new Promise((resolve) => setTimeout(resolve, 0));
}
