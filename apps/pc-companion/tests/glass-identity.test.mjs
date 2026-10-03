import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
const companionRoot = resolve(here, "..");
const repoRoot = resolve(companionRoot, "../..");

const GLASS_VERSION = "1.6.0-alpha.43";
const GLASS_DESCRIPTION = "Cyclone Glass";
const INSTALLER_PRODUCT_NAME = "Cyclone One";

function tomlString(source, key) {
  const match = source.match(new RegExp(`^${key}\\s*=\\s*"([^"]+)"`, "m"));
  assert.ok(match, `expected ${key} in TOML`);
  return match[1];
}

test("package.json is Glass 1.6.0-alpha.43", () => {
  const pkg = JSON.parse(readFileSync(resolve(companionRoot, "package.json"), "utf8"));
  assert.equal(pkg.version, GLASS_VERSION);
  assert.equal(pkg.description, GLASS_DESCRIPTION);
});

test("tauri window title is Cyclone Glass; installer productName stays Cyclone One", () => {
  const tauri = JSON.parse(readFileSync(resolve(companionRoot, "src-tauri/tauri.conf.json"), "utf8"));
  assert.equal(tauri.version, GLASS_VERSION);
  assert.equal(tauri.productName, INSTALLER_PRODUCT_NAME);
  assert.equal(tauri.app.windows[0].title, GLASS_DESCRIPTION);
});

test("Cargo.toml crate version is 1.6.0-alpha.43", () => {
  const cargo = readFileSync(resolve(companionRoot, "src-tauri/Cargo.toml"), "utf8");
  const packageBlock = cargo.match(/\[package\][\s\S]*?(?=\n\[|$)/);
  assert.ok(packageBlock, "expected [package] in Cargo.toml");
  assert.equal(tomlString(packageBlock[0], "version"), GLASS_VERSION);
});

test("version.toml keeps the frozen PC side coherent while the phone moves on", () => {
  // Since the fast release lane, only Android and Glass ship. The companion, gateway and MCP stay frozen together at
  // the alpha.43 build that is installed on the PC; the phone version moves on without them.
  const versionToml = readFileSync(resolve(repoRoot, "release/version.toml"), "utf8");
  const mobileVersion = tomlString(versionToml, "mobile");
  assert.match(mobileVersion, /^5\.0\.0-alpha\.\d+(?:\.dev\d+)?$/);
  assert.equal(tomlString(versionToml, "pc_companion"), GLASS_VERSION);
  const pcPython = tomlString(versionToml, "python_version");
  assert.match(pcPython, /^5\.0\.0-alpha\.\d+(?:\.dev\d+)?$/);
  assert.equal(tomlString(versionToml, "device_gateway"), pcPython);
  assert.equal(tomlString(versionToml, "mcp"), pcPython);
  assert.equal(tomlString(versionToml, "product_version"), mobileVersion);
  assert.match(
    readFileSync(resolve(repoRoot, "apps/mobile/app/build.gradle.kts"), "utf8"),
    new RegExp(`versionName = "${mobileVersion.replaceAll(".", "\\.")}"`),
  );
  assert.equal(
    tomlString(readFileSync(resolve(repoRoot, "apps/device-gateway/pyproject.toml"), "utf8"), "version"),
    pcPython,
  );
});
