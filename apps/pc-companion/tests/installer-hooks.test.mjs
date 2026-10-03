import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
const hooks = readFileSync(resolve(here, "../src-tauri/windows/installer-hooks.nsh"), "utf8");

function macro(name) {
  const match = hooks.match(new RegExp(`!macro ${name}\\r?\\n([\\s\\S]*?)!macroend`));
  assert.ok(match, `expected macro ${name}`);
  return match[1];
}

test("install and uninstall stop Cyclone's own adb before touching android-platform-tools", () => {
  // alpha.27: "Error opening file for writing: ...\\Cyclone One\\android-platform-tools\\adb.exe"
  for (const hook of ["NSIS_HOOK_PREINSTALL", "NSIS_HOOK_PREUNINSTALL"]) {
    assert.match(macro(hook), /!insertmacro CYCLONE_STOP_BUNDLED_ADB/, hook);
  }
  const stop = macro("CYCLONE_STOP_BUNDLED_ADB");
  assert.match(stop, /adb\.exe" kill-server/);
  assert.match(stop, /\\Cyclone One\\android-platform-tools\\/);
});

test("only Cyclone's bundled adb is stopped, never an Android Studio or system adb", () => {
  assert.doesNotMatch(hooks, /taskkill[^\n]*\/IM adb\.exe/i);
  assert.match(macro("CYCLONE_STOP_BUNDLED_ADB"), /Where-Object \{ \$\$_\.Path -like/);
});
