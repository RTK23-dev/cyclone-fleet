import test from "node:test";
import assert from "node:assert/strict";
import { livePhoneLabels } from "../.test-dist/core/livePhone.js";
import { DIRECT_LIVE_PHONE_AGENT_PROMPT, directLivePhoneHandoff } from "../.test-dist/core/directLivePhone.js";

test("Live Phone excludes virtual devices and does not invent readiness", () => {
  const labels = livePhoneLabels(
    {connected:false,vision:false,control:false,enabled:true,stopped:false},
    [{source:"VIRTUAL",name:"Virtual",connectionLabel:"Ready"},{source:"USB",name:"Pixel",connectionLabel:"Not paired"}],
  );
  assert.equal(labels.phone,"Pixel · Not paired");
  assert.match(labels.control,/waiting/);
  assert.doesNotMatch(labels.vision,/Ready/);
  assert.match(labels.cloud,/Waiting/);
});

test("Direct connector readiness is distinct from fallback PC activity", () => {
  const labels = livePhoneLabels({connected:true,cloud_connector_ready:true,live_phone_broker_ready:true,gateway_ready:true,accessibility_ready:true,vision:true,control:true,enabled:true,stopped:false}, []);
  assert.match(labels.cloud,/Direct cloud connector/);
  assert.match(labels.broker,/Ready/);
  assert.match(labels.gateway,/Ready/);
  assert.match(labels.accessibility,/Ready/);
  assert.equal(labels.control,"Control · Ready");
});

test("Stop overrides old ready status", () => {
  const labels=livePhoneLabels({connected:true,vision:false,control:true,enabled:false,stopped:true},[]);
  assert.equal(labels.control,"Control · Stopped");
  assert.match(labels.phone,/Connect and pair/);
});

test("Direct Live Phone prompt teaches typed foreground MCP without routing Codex", () => {
  assert.match(DIRECT_LIVE_PHONE_AGENT_PROMPT,/cyclone_phone_devices/);
  assert.match(DIRECT_LIVE_PHONE_AGENT_PROMPT,/observation_id/);
  assert.match(DIRECT_LIVE_PHONE_AGENT_PROMPT,/default-foreground/);
  assert.match(DIRECT_LIVE_PHONE_AGENT_PROMPT,/Never retry an uncertain mutation/);
  assert.doesNotMatch(DIRECT_LIVE_PHONE_AGENT_PROMPT,/CycloneAgentMCP/);
});

test("one-step cloud handoff carries one connector bundle with an explicit privacy warning", () => {
  const handoff = directLivePhoneHandoff("https://example.test/mcp", "private-bearer-value");
  assert.match(handoff,/CONNECTION HANDOFF/);
  assert.match(handoff,/https:\/\/example\.test\/mcp/);
  assert.match(handoff,/private-bearer-value/);
  assert.match(handoff,/Do not paste it into a normal AI chat/);
  assert.match(handoff,/cyclone_phone_devices/);
});
