import test from "node:test";
import assert from "node:assert/strict";
import { installMiniDom } from "./helpers/mini-dom.mjs";
import { fakeGateway, flush } from "./helpers/fakeGateway.mjs";
import { GatewayClient } from "../.test-dist/services/gateway.js";
import { INFO } from "../.test-dist/services/delivery.js";
import { AEAD_AES256GCM, fingerprint, schedule, unhex } from "../.test-dist/services/hpke.js";
import { createVault, fromB64, toB64 } from "../.test-dist/services/vault.js";
import { parsePrepared } from "../.test-dist/services/signup.js";
import { createAccountsPanel } from "../.test-dist/pages/createAccounts.js";

// The RFC 9180 A.3 recipient key plays the phone.
const skRm = "f3ce7fdae57e1a310d87f1ebbde6f328be0a99cdbcadf4d6589cf29de4b8ffd2";
const pkRm = unhex("04fe8c19ce0905191ebc298a9245792531f26f0cece2460639e8bc39cb7f706a826a779b4cf969b8a0e539c7f62fb3d30ad6aa8f80e30f1d128aafd68a2ce72ea0");
const b64url = (b) => Buffer.from(b).toString("base64url");
async function phoneOpens(envelope) {
  const jwk = { kty: "EC", crv: "P-256", d: b64url(unhex(skRm)), x: b64url(pkRm.slice(1, 33)), y: b64url(pkRm.slice(33)) };
  const sk = await crypto.subtle.importKey("jwk", jwk, { name: "ECDH", namedCurve: "P-256" }, false, ["deriveBits"]);
  const enc = fromB64(envelope.enc);
  const pkE = await crypto.subtle.importKey("raw", enc, { name: "ECDH", namedCurve: "P-256" }, false, []);
  const dh = new Uint8Array(await crypto.subtle.deriveBits({ name: "ECDH", public: pkE }, sk, 256));
  const ks = await schedule(dh, enc, pkRm, new TextEncoder().encode(INFO), AEAD_AES256GCM);
  const key = await crypto.subtle.importKey("raw", ks.key, { name: "AES-GCM" }, false, ["decrypt"]);
  const plain = await crypto.subtle.decrypt({ name: "AES-GCM", iv: ks.baseNonce, additionalData: new TextEncoder().encode(envelope.aad) }, key, fromB64(envelope.ct));
  return new TextDecoder().decode(plain);
}

const PASS = "orbit maple lantern quiet river";
const ctx = (fetch) => ({ client: new GatewayClient({ token: "t", fetch }), version: "1.0.0-alpha.44", devices: [{ id: "pixel8-abc", name: "Pixel 8" }],
  device: null, devicesError: null, navigate() {}, selectDevice() {}, refreshDevices: async () => {} });
async function until(done, ms = 5000) {
  const end = Date.now() + ms;
  while (!done() && Date.now() < end) await new Promise((resolve) => setTimeout(resolve, 5));
  await flush();
}
const button = (root, label) => root.querySelectorAll("button").find((b) => b.textContent === label);

test("prepared rows parse defensively", () => {
  const p = parsePrepared({ tableId: "tb_1", app: "Instagram", package: "com.instagram.android",
    rows: [{ rowId: "rw_1", title: "Brand One", accountId: "acc_1", vaultItemId: null, username: "brandone", deviceId: "pixel8-abc" },
      { rowId: "rw_2", title: "Brand Two", error: "That account is already in the Command Center." }] });
  assert.equal(p.rows[0].vaultItemId, null);
  assert.equal(p.rows[1].error, "That account is already in the Command Center.");
});

test("Create accounts: confirm, a new vault password per account, sealed to the phone when its task waits", async () => {
  installMiniDom();
  const made = await createVault(PASS, 1000);
  const meta = { ...made.init, keyVersion: 1 };
  const saved = [];
  const created = [];
  const submitted = [];
  let pendingNow = [];
  const gateway = fakeGateway({
    "POST /v1/cc/signup/prepare": () => ({ tableId: "tb_signups00001", app: "Instagram", package: "com.instagram.android",
      rows: [{ rowId: "rw_brand00001", title: "Brand One", accountId: "acc_brand0001", vaultItemId: null, username: "brandone", deviceId: "pixel8-abc" },
        { rowId: "rw_brand00002", title: "Brand Two", error: "That account is already in the Command Center." }] }),
    "GET /v1/cc/vault": () => ({ exists: true, format: 1, minIterations: 1000, meta, items: [] }),
    "POST /v1/cc/vault/items": ({ body }) => { saved.push(body); return { ...body, createdAt: 1, updatedAt: 1 }; },
    "POST /v1/cc/signup/create": ({ body }) => { created.push(body); pendingNow = [{ taskId: "tsk_brand0001", title: "Create", deviceId: "pixel8-abc",
      vaultItemId: body.rows[0].vaultItemId, accountId: "acc_brand0001", handle: "Brand One", place: "package:com.instagram.android", dueAt: null,
      deviceKey: null }]; return { started: [{ rowId: "rw_brand00001", taskId: "tsk_brand0001" }], errors: [] }; },
    "GET /v1/cc/leases/pending": () => ({ pending: pendingNow }),
    "POST /v1/cc/tasks/tsk_brand0001/leases": ({ body }) => { submitted.push(body); pendingNow = []; return { accepted: true }; },
  });
  const notes = [];
  const panel = createAccountsPanel(ctx(gateway.fetch), "tb_signups00001", (text, tone) => notes.push([text, tone]));
  try {
    button(panel.element, "Create accounts").click();
    await flush();
    assert.match(panel.element.textContent, /Brand One/);
    assert.match(panel.element.textContent, /already in the Command Center/);
    assert.match(panel.element.textContent, /without asking again/);
    const pass = panel.element.querySelectorAll("input").find((i) => i.getAttribute("aria-label") === "Vault passphrase");
    pass.value = PASS;
    button(panel.element, "Create 1 account").click();
    await until(() => created.length === 1 || notes.some(([, tone]) => tone === "error"));
    assert.equal(saved.length, 1, JSON.stringify(notes));
    assert.equal(saved[0].accountId, "acc_brand0001");
    assert.equal(saved[0].kind, "login");
    assert.deepEqual(created, [{ tableId: "tb_signups00001", rows: [{ rowId: "rw_brand00001", accountId: "acc_brand0001", vaultItemId: saved[0].id }] }]);
    assert.equal(submitted.length, 0, "not before the phone's key is trusted");
    // The phone's key becomes trusted; the next pass seals the password to it.
    pendingNow = [{ ...pendingNow[0], deviceKey: { publicKey: toB64(pkRm), fingerprint: await fingerprint(pkRm) } }];
    await panel.sealNow();
    assert.equal(submitted.length, 1, JSON.stringify(notes) + panel.element.textContent);
    const [envelope] = submitted[0].envelopes;
    const password = await phoneOpens(envelope);
    assert.equal(password.length, 20);
    // The password is in no request but the sealed envelope.
    for (const call of gateway.calls) assert.ok(!JSON.stringify(call.body ?? {}).includes(password));
    assert.ok(notes.some(([text]) => /Started 1 account/.test(text)));
  } finally {
    panel.destroy();
  }
});

test("Create accounts without a Ready row says so, and Pause all reports what it stopped", async () => {
  installMiniDom();
  const gateway = fakeGateway({
    "POST /v1/cc/signup/prepare": () => ({ tableId: "tb_signups00001", app: "Instagram", package: "com.instagram.android", rows: [] }),
    "POST /v1/cc/signup/pause": () => ({ stopped: ["rw_1", "rw_2"] }),
  });
  const notes = [];
  const panel = createAccountsPanel(ctx(gateway.fetch), "tb_signups00001", (text) => notes.push(text));
  try {
    button(panel.element, "Create accounts").click();
    await flush();
    assert.ok(notes.some((t) => /No row is Ready/.test(t)));
    button(panel.element, "Pause all").click();
    await flush();
    assert.ok(notes.some((t) => /Paused 2 accounts/.test(t)));
  } finally {
    panel.destroy();
  }
});
