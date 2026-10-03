"""A small local MCP server over stdio (plan 34 tests): it reads, writes a file in its own folder, and reaches an
outside service over HTTP and brings the answer back. It also writes its env key to stderr, which Cyclone must hide."""
import json
import os
import sys
import urllib.request

TOOLS = [
    {"name": "get_weather", "description": "Ask the weather service.", "annotations": {"readOnlyHint": True},
     "inputSchema": {"type": "object", "required": ["url"], "properties": {"url": {"type": "string"}}}},
    {"name": "write_note", "description": "Write a note file.", "inputSchema": {"type": "object", "required": ["text"], "properties": {"text": {"type": "string"}}}},
    {"name": "send_email", "description": "Send an email.", "inputSchema": {"type": "object", "properties": {"to": {"type": "string"}}}},
]

sys.stderr.write(f"booting with key {os.environ.get('WEATHER_KEY', '')}\n")
sys.stderr.flush()
if os.environ.get("CYCLONE_DEVICE_GATEWAY_TOKEN"):
    sys.stderr.write("LEAK: the gateway token reached me\n")
    sys.stderr.flush()
for line in sys.stdin:
    message = json.loads(line)
    if "id" not in message:
        continue
    method = message["method"]
    if method == "initialize":
        result = {"protocolVersion": "2025-06-18", "capabilities": {"tools": {}}, "serverInfo": {"name": "fake-local"}}
    elif method == "tools/list":
        result = {"tools": TOOLS}
    elif method == "tools/call":
        name, args = message["params"]["name"], message["params"].get("arguments", {})
        if name == "get_weather":
            request = urllib.request.Request(args["url"], headers={"X-Key": os.environ.get("WEATHER_KEY", "")})
            data = json.loads(urllib.request.urlopen(request, timeout=10).read())
            result = {"content": [{"type": "text", "text": json.dumps(data)}], "structuredContent": data}
        elif name == "write_note":
            with open("note.txt", "w", encoding="utf-8") as handle:
                handle.write(args["text"])
            result = {"content": [{"type": "text", "text": f"wrote {len(args['text'])} characters to {os.path.abspath('note.txt')}"}]}
        else:
            result = {"content": [{"type": "text", "text": "sent"}]}
    else:
        print(json.dumps({"jsonrpc": "2.0", "id": message["id"], "error": {"code": -32601, "message": "no"}}), flush=True)
        continue
    print(json.dumps({"jsonrpc": "2.0", "id": message["id"], "result": result}), flush=True)
