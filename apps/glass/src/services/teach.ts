/**
 * Follow Me from Glass: start teaching on the phone, the developer shows the route on the phone itself, then Done.
 * The phone records the demonstration and promotes it into its Atlas ("Your teaching"). Glass only starts and stops.
 */
import type { GatewayClient } from "./gateway.js";

export async function startTeaching(client: GatewayClient, deviceId: string, goal: string): Promise<void> {
  await client.post(`/v1/devices/${encodeURIComponent(deviceId)}/agent/teach/start`, { goal: goal.slice(0, 200) });
}

export async function stopTeaching(client: GatewayClient, deviceId: string): Promise<string> {
  const body = await client.post<{ teaching?: { summary?: unknown } }>(`/v1/devices/${encodeURIComponent(deviceId)}/agent/teach/stop`, {
    compile_for_review: true,
  });
  return typeof body?.teaching?.summary === "string" ? body.teaching.summary : "";
}
