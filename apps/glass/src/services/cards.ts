/**
 * Connector cards (plan 34 M4). A card says where a connector is, how it signs in (the method, never a key), and which
 * tools to switch on. The gateway checks an imported card like any new connection: an API description is read again,
 * a program still shows its setup card, and a tool is switched on only when it matches the card.
 *
 * The curated cards below ship with Glass. They name reading tools only (no hash): those are switched on by name,
 * and every tool that changes something stays off until the owner ticks it.
 */

export interface CuratedCard {
  id: string;
  title: string;
  /** What the owner needs before it works, in plain words. */
  needs: string;
  /** Whether this card's tools were checked against the live service when it was written. */
  verified: boolean;
  card: Record<string, unknown>;
}

const OPEN_METEO = {
  openapi: "3.0.3",
  info: { title: "Open-Meteo weather", version: "1" },
  servers: [{ url: "https://api.open-meteo.com/v1" }],
  paths: {
    "/forecast": {
      get: {
        operationId: "forecast",
        summary: "Weather forecast for a place",
        description: "Current weather and forecasts for a latitude and longitude. Variables are comma-separated, such as temperature_2m,wind_speed_10m,precipitation.",
        parameters: [
          { name: "latitude", in: "query", required: true, schema: { type: "number" }, description: "Latitude, for example 52.09 (Utrecht)" },
          { name: "longitude", in: "query", required: true, schema: { type: "number" }, description: "Longitude, for example 5.12 (Utrecht)" },
          { name: "current", in: "query", schema: { type: "string" }, description: "Current variables, e.g. temperature_2m,weather_code,wind_speed_10m" },
          { name: "hourly", in: "query", schema: { type: "string" }, description: "Hourly variables, e.g. temperature_2m,precipitation_probability" },
          { name: "daily", in: "query", schema: { type: "string" }, description: "Daily variables, e.g. temperature_2m_max,temperature_2m_min,precipitation_sum" },
          { name: "timezone", in: "query", schema: { type: "string", default: "auto" }, description: "auto, or a zone like Europe/Amsterdam" },
          { name: "forecast_days", in: "query", schema: { type: "integer" }, description: "1 to 16 days" },
        ],
      },
    },
  },
};

const GITHUB = {
  openapi: "3.0.3",
  info: { title: "GitHub", version: "2022-11-28" },
  servers: [{ url: "https://api.github.com" }],
  security: [{ github: [] }],
  components: { securitySchemes: { github: { type: "http", scheme: "bearer" } } },
  paths: {
    "/user": { get: { operationId: "whoAmI", summary: "The signed-in account" } },
    "/repos/{owner}/{repo}": {
      get: {
        operationId: "getRepo",
        summary: "A repository",
        parameters: [
          { name: "owner", in: "path", required: true, schema: { type: "string" } },
          { name: "repo", in: "path", required: true, schema: { type: "string" } },
        ],
      },
    },
    "/repos/{owner}/{repo}/issues": {
      get: {
        operationId: "listIssues",
        summary: "Issues in a repository",
        parameters: [
          { name: "owner", in: "path", required: true, schema: { type: "string" } },
          { name: "repo", in: "path", required: true, schema: { type: "string" } },
          { name: "state", in: "query", schema: { type: "string", enum: ["open", "closed", "all"] } },
          { name: "per_page", in: "query", schema: { type: "integer" } },
        ],
      },
      post: {
        operationId: "createIssue",
        summary: "Open an issue",
        parameters: [
          { name: "owner", in: "path", required: true, schema: { type: "string" } },
          { name: "repo", in: "path", required: true, schema: { type: "string" } },
        ],
        requestBody: {
          required: true,
          content: { "application/json": { schema: { type: "object", required: ["title"], properties: { title: { type: "string" }, body: { type: "string" } } } } },
        },
      },
    },
  },
};

const card = (name: string, kind: string, where: Record<string, unknown>, tools: string[], note: string): Record<string, unknown> => ({
  cyclone: "connector-card", version: 1, name, kind, ...where, note,
  tools: tools.map((tool) => ({ name: tool, allowed: true, rule: "cap" })),
  dailyCap: 50, approval: "always",
});

export const CURATED: CuratedCard[] = [
  {
    id: "open-meteo", title: "Weather (Open-Meteo)", verified: false,
    needs: "Nothing: it is free and needs no key. Its forecast tool reads, so it is on.",
    card: card("Weather", "api", { api: OPEN_METEO }, ["forecast"], "Open-Meteo's free forecast API. Reading only."),
  },
  {
    id: "github", title: "GitHub", verified: false,
    needs: "A token from GitHub → Settings → Developer settings → Personal access tokens. Reading tools are on; opening an issue asks you first.",
    card: card("GitHub", "api", { api: GITHUB }, ["whoAmI", "getRepo", "listIssues"], "A few GitHub REST calls. Opening an issue is off until you tick it."),
  },
  {
    id: "higgsfield", title: "Higgsfield", verified: false,
    needs: "Sign in on Higgsfield's page. Its tools are listed after you sign in; tick the ones to use.",
    card: card("Higgsfield", "remote", { remote: { url: "https://mcp.higgsfield.ai/mcp", transport: "http" } }, [],
      "Higgsfield's MCP server. Its tool names are not published, so this card switches none on."),
  },
];
