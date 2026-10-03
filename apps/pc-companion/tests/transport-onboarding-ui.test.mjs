import assert from "node:assert/strict";
import test from "node:test";
import fs from "node:fs";

const source = fs.readFileSync(new URL("../src/ui/transportOnboarding.ts", import.meta.url), "utf8");
const mainSource = fs.readFileSync(new URL("../src/main.ts", import.meta.url), "utf8");

test("transport onboarding UI exposes USB, Wireless and VMOS first-connect paths", () => {
  assert.match(source, /Connect phone/);
  assert.match(source, /Check USB/);
  assert.match(source, /Pair & connect/);
  assert.match(source, /Connect VMOS phone/);
  assert.match(source, /Developer options → USB debugging/);
  assert.match(source, /Wireless debugging/);
});

test("real Cyclone One bootstrap mounts the always-available Connect phone surface after app start", () => {
  const startIndex = mainSource.indexOf("await app.start()");
  const mountIndex = mainSource.indexOf("mountTransportOnboarding(service)");
  assert.ok(startIndex > 0 && mountIndex > startIndex);
  assert.match(mainSource, /import \{ mountTransportOnboarding \} from "\.\/ui\/transportOnboarding\.js"/);
  assert.match(source, /position: "fixed"/);
  assert.match(source, /document\.body\.append\(dialog, launcher\)/);
});

test("first-run copy is self-contained and carries VMOS, Mobile and trust-pairing handoff", () => {
  assert.match(source, /Android Platform-Tools 37\.0\.1/);
  assert.match(source, /no Android Studio or separate ADB install/);
  assert.match(source, /Recommended VMOS image: Android 15/);
  assert.match(source, /Android 13\/14 are compatible/);
  assert.match(source, /Android 12 and older are unsupported/);
  assert.match(source, /Cyclone Mobile 4\.3\.6/);
  assert.match(source, /PC Gateway & QR pairing/);
});

test("wireless pairing code is cleared before async submit and never logged", () => {
  const clearIndex = source.indexOf('code.input.value = ""');
  const submitIndex = source.indexOf("client.pairWireless");
  assert.ok(clearIndex > 0 && submitIndex > clearIndex);
  assert.doesNotMatch(source, /console\.(log|error|warn).*pair/i);
  assert.match(source, /type = "password"|field\("6-digit pairing code"/);
});

test("successful transport invokes normal Cyclone fleet rescan and names the trust-pairing next step", () => {
  assert.match(source, /await service\.scanDevices\(\)/);
  assert.match(source, /transport route already refreshes DeviceFleetManager/);
  assert.match(source, /Next: open Cyclone Mobile → PC Gateway & QR pairing to finish trust pairing/);
});
