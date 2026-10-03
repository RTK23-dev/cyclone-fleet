/** One place that builds the phone-owned data client (atlas, mapping, Ask) for the chosen phone. */
import type { GlassContext } from "../app.js";
import { createAtlasClient, type AtlasClient } from "./atlasClient.js";

/** Glass drives the phone's main screen in alpha.1; named workspaces arrive with session tiles later. */
export const FOREGROUND_SESSION_ID = "default-foreground";

export function phoneClient(ctx: GlassContext, deviceId: string, fetchImpl?: typeof fetch): AtlasClient {
  return createAtlasClient({
    baseUrl: ctx.client.baseUrl,
    getBearer: () => ctx.client.bearer(),
    getSessionId: () => FOREGROUND_SESSION_ID,
    getDeviceId: () => deviceId,
    getPhoneVersion: () => ctx.devices.find((device) => device.id === deviceId)?.mobileVersion ?? null,
    fetch: fetchImpl ?? ((input, init) => globalThis.fetch(input, init)),
  });
}

export function mappingErrorCopy(code: string): string {
  switch (code) {
    case "MAPPING_PLANE_BUSY":
      return "A mapping pass is already running on this phone.";
    case "HUMAN_HAS_CONTROL":
      return "You have control of the phone. Give it back to Cyclone, then start mapping.";
    case "SESSION_REQUIRED":
    case "SESSION_DISPLAY_MISMATCH":
      return "Cyclone can't see the phone's main screen. Check Cyclone's accessibility service on the phone.";
    case "PLACE_NOT_LAUNCHABLE":
      return "Glass maps installed apps in this alpha. Websites come later.";
    default:
      return `Mapping couldn't start${code ? ` (${code})` : ""}.`;
  }
}
