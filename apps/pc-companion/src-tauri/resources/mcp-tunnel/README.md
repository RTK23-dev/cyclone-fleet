# Cyclone One remote MCP tunnel

Bearer-authenticated HTTPS front for ChatGPT and Grok **chat** connectors.

```
ChatGPT / Grok chat  --HTTPS-->  cloudflared
                                      |
                                      v
                             127.0.0.1:8787  auth gateway
                               |  Authorization: Bearer required
                               |  GATEWAY_MODE=readonly (default)
                               v
                         CycloneAgentMCP.exe serve   (stdio)
```

This pack is installed to `%LOCALAPPDATA%\Cyclone One\mcp-tunnel\` and is started from **Settings → Remote MCP (ChatGPT / Grok chat)**. It does **not** change `~/.grok/config.toml` or Cursor stdio MCP.

Fail closed: there is no anonymous public phone endpoint. Missing/wrong bearer → HTTP 401. `GATEWAY_MODE=full` still requires the bearer and exposes mutating tools to whoever has the token.
