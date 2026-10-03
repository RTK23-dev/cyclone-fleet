import test from "node:test";
import assert from "node:assert/strict";
import { LAYER2_PLANE, LAYER2_STRIP_COPY } from "../.test-dist/core/layer2.js";
import {
  CODEX_MCP_PROMPT,
  DEFAULT_FOREGROUND_SESSION_ID,
  FOREGROUND_PLANE_COPY,
  FOREGROUND_PLANE_LABEL,
  HOME_MCP_SESSION_COPY,
  MCP_FOREGROUND_OPERATOR_LINE,
  MCP_FOREGROUND_SESSION_COPY,
  SESSION_TILES_COPY,
  SETTINGS_MCP_SESSION_COPY,
  VD_PLANE_LABEL,
} from "../.test-dist/core/sessionTiles.js";

const FOREGROUND_SESSION_TOKEN = `session_id=${DEFAULT_FOREGROUND_SESSION_ID}`;

test("operator MCP copy names default-foreground for live observe/act/locate", () => {
  assert.equal(DEFAULT_FOREGROUND_SESSION_ID, "default-foreground");
  assert.equal(
    MCP_FOREGROUND_SESSION_COPY,
    "MCP observe/act/locate require session_id=default-foreground for the live human display (display 0).",
  );
  assert.match(MCP_FOREGROUND_SESSION_COPY, /observe\/act\/locate/);
  assert.match(MCP_FOREGROUND_SESSION_COPY, new RegExp(FOREGROUND_SESSION_TOKEN));
  assert.match(MCP_FOREGROUND_OPERATOR_LINE, new RegExp(FOREGROUND_SESSION_TOKEN));
  assert.match(MCP_FOREGROUND_OPERATOR_LINE, /Foreground JPEG \(display 0\)/);
  assert.match(MCP_FOREGROUND_OPERATOR_LINE, /display_id>0/);
  assert.match(CODEX_MCP_PROMPT, new RegExp(FOREGROUND_SESSION_TOKEN));
  assert.match(CODEX_MCP_PROMPT, /observe/);
  assert.match(HOME_MCP_SESSION_COPY, new RegExp(FOREGROUND_SESSION_TOKEN));
  assert.match(HOME_MCP_SESSION_COPY, /Live control and MCP/);
  assert.equal(SETTINGS_MCP_SESSION_COPY, `${MCP_FOREGROUND_SESSION_COPY} Doctor also reports this.`);
  assert.match(FOREGROUND_PLANE_COPY, new RegExp(FOREGROUND_SESSION_TOKEN));
  assert.match(FOREGROUND_PLANE_COPY, /display 0/);
});

test("operator copy keeps Foreground, Session Kernel VD, and Layer 2 as distinct planes", () => {
  assert.equal(FOREGROUND_PLANE_LABEL, "Foreground");
  assert.equal(VD_PLANE_LABEL, "Session Kernel VD");
  assert.notEqual(FOREGROUND_PLANE_LABEL, VD_PLANE_LABEL);
  assert.notEqual(LAYER2_PLANE, "session_kernel_vd");
  assert.notEqual(LAYER2_PLANE, "foreground");
  assert.match(SESSION_TILES_COPY, /not Layer 2/i);
  assert.match(SESSION_TILES_COPY, /displayId>0/);
  assert.doesNotMatch(SESSION_TILES_COPY, new RegExp(DEFAULT_FOREGROUND_SESSION_ID));
  assert.doesNotMatch(FOREGROUND_PLANE_COPY, /Layer 2/);
  assert.doesNotMatch(FOREGROUND_PLANE_COPY, /displayId>0|display_id>0/);
  assert.doesNotMatch(LAYER2_STRIP_COPY, /session_id/);
  assert.match(MCP_FOREGROUND_OPERATOR_LINE, /Named VD tiles use their own session_id plus display_id>0/);
});
