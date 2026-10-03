export interface DirectLiveBridgeStatus {
  running: boolean;
  url?: string | null;
  transport?: string;
}

export const DIRECT_LIVE_PHONE_AGENT_PROMPT = `You are connected to my physical Android phone through Cyclone Live Phone.

Use the cyclone_phone_* tools directly instead of telling me what to tap.
1. Start with cyclone_phone_devices and choose the ready physical phone. If more than one is ready and I did not name one, ask which phone.
2. Live Phone always targets default-foreground / display 0, the screen I can see.
3. Observe before acting with cyclone_phone_observe or cyclone_phone_screenshot.
4. Prefer cyclone_phone_locate / cyclone_phone_search_ui / cyclone_phone_inspect over guessed coordinates.
5. Every action must use the current observation_id. After each meaningful action, observe again and verify the result.
6. Use cyclone_phone_tap, type, clear_text, scroll, back, home, open_app and the other typed tools. Never invent shell, ADB, PowerShell, URLs, tokens, or background sessions.
7. Respect Cyclone Pause/Stop and Android confirmations. Do not expose passwords, OTPs, bearer tokens, payment data, or other secrets.
8. Never retry an uncertain mutation. Observe again first.

Complete the requested phone task, verify the final state, then briefly report what was done.`;

/**
 * One clipboard handoff for connector setup. It deliberately carries the bearer
 * credential because the user asked for one setup step; the warning makes the
 * destination explicit so it is never pasted into an ordinary AI message.
 */
export function directLivePhoneHandoff(url: string, token: string): string {
  return `CYCLONE LIVE PHONE — CONNECTION HANDOFF

PRIVATE SETUP BUNDLE
Paste this only into a connector / Remote MCP setup flow. Do not paste it into a normal AI chat.

Name: Cyclone Live Phone
Remote MCP URL: ${url}
Authentication: Bearer token
Bearer token: ${token}

After the connector is added, use these agent instructions:

${DIRECT_LIVE_PHONE_AGENT_PROMPT}`;
}
