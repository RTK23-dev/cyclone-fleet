import test from "node:test";
import assert from "node:assert/strict";
import { installMiniDom } from "./helpers/mini-dom.mjs";
import { fakeGateway } from "./helpers/fakeGateway.mjs";
import { GatewayClient } from "../.test-dist/services/gateway.js";
import { createVaultView } from "../.test-dist/pages/vaultView.js";

const PASS = "orbit maple lantern quiet river";
const CANARY = "CANARY-9d41-never-leaves-the-tab";

function statefulVault() {
  let vault = { exists: false, format: 1, minIterations: 600000, items: [] };
  const audits = [];
  const gateway = fakeGateway({
    "GET /v1/cc/vault": () => vault,
    "POST /v1/cc/vault/init": ({ body }) => {
      vault = { exists: true, format: 1, minIterations: 600000, meta: { ...body, keyVersion: 1, createdAt: 1, updatedAt: 1 }, items: [] };
      return vault;
    },
    "POST /v1/cc/vault/items": ({ body }) => {
      const record = { ...body, createdBy: "owner", createdAt: 2, updatedAt: 2 };
      vault = { ...vault, items: [...vault.items.filter((i) => i.id !== body.id), record] };
      return record;
    },
    "POST /v1/cc/vault/audit": ({ body }) => {
      audits.push(body.action);
      return { recorded: true };
    },
  });
  return { gateway, audits, state: () => vault };
}

async function until(check, label, ms = 15000) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    if (check()) return;
    await new Promise((r) => setTimeout(r, 20));
  }
  assert.fail(`timed out: ${label}`);
}

const buttons = (root, label) => root.querySelectorAll("button").filter((b) => b.textContent === label);
const inputs = (root, label) => root.querySelectorAll("input").filter((i) => i.getAttribute("aria-label") === label);

test("create, save, lock and unlock: no secret, passphrase or recovery key ever reaches the gateway", async () => {
  installMiniDom();
  const { gateway, audits, state } = statefulVault();
  const said = [];
  const ctx = { client: new GatewayClient({ token: "t", fetch: gateway.fetch }) };
  const view = createVaultView(ctx, () => [], (text) => said.push(text));
  const root = view.element;
  try {
  await until(() => buttons(root, "Create vault").length, "create card");

  inputs(root, "Passphrase")[0].value = PASS;
  inputs(root, "Passphrase again")[0].value = PASS;
  buttons(root, "Create vault")[0].click();
  await until(() => root.querySelector(".vault-recovery"), "recovery key shown");
  const recoveryKey = root.querySelector(".vault-recovery").textContent;
  assert.match(recoveryKey, /^([A-Z2-7]{4}-){12}[A-Z2-7]{4}$/);
  buttons(root, "Continue")[0].click();
  assert.match(said.at(-1), /Tick the box/);
  root.querySelectorAll("input").find((i) => i.type === "checkbox").checked = true;
  buttons(root, "Continue")[0].click();
  await until(() => buttons(root, "Add item").length, "unlocked view");

  buttons(root, "Add item")[0].click();
  inputs(root, "Name")[0].value = "Shop admin";
  inputs(root, "Username or email")[0].value = "me@shop.test";
  inputs(root, "Password, seed or codes")[0].value = CANARY;
  buttons(root, "Save")[0].click();
  await until(() => state().items.length === 1 && /Shop admin/.test(root.textContent), "item saved and listed");
  assert.equal(root.textContent.includes(CANARY), false, "the secret is masked until shown");

  buttons(root, "Show")[0].click();
  await until(() => root.textContent.includes(CANARY), "shown within the step-up window");
  assert.ok(audits.includes("reveal"));

  buttons(root, "Lock")[0].click();
  await until(() => buttons(root, "Unlock").length, "locked");
  assert.equal(root.textContent.includes(CANARY), false, "locking drops the secret from the page");
  inputs(root, "Passphrase")[0].value = "wrong passphrase entirely";
  buttons(root, "Unlock")[0].click();
  await until(() => said.some((t) => /does not open/.test(t)), "wrong passphrase refused");
  inputs(root, "Passphrase")[0].value = PASS;
  buttons(root, "Unlock")[0].click();
  await until(() => /Shop admin/.test(root.textContent), "unlocked again and decrypted");

  const sent = JSON.stringify(gateway.calls.map((c) => c.body ?? null));
  for (const needle of [CANARY, PASS, recoveryKey, recoveryKey.replace(/-/g, ""), "me@shop.test", "Shop admin"]) {
    assert.equal(sent.includes(needle), false, `the gateway never saw ${needle.slice(0, 12)}…`);
  }
  assert.ok(audits.includes("unlock") && audits.includes("unlock_failed") && audits.includes("lock"));
  } finally {
    view.destroy();
  }
});
