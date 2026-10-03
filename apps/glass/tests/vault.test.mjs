import test from "node:test";
import assert from "node:assert/strict";
import {
  VaultLockedError, createVault, decryptItem, emptyFields, encryptItem, formatRecovery, generatePassword, health,
  newItemId, newRecoveryKey, parseRecovery, random, rewrapPassphrase, strength, totpCode, unlock,
} from "../.test-dist/services/vault.js";
import { csvRows, parseExport } from "../.test-dist/services/vaultImport.js";

const PASS = "orbit maple lantern quiet river";
const meta = (made) => ({ ...made.init, keyVersion: 1, createdAt: 0, updatedAt: 0 });

test("a vault opens with its passphrase or recovery key and nothing else", async () => {
  const made = await createVault(PASS);
  assert.equal(made.init.kdf.iterations, 600000);
  assert.match(made.recoveryKey, /^([A-Z2-7]{4}-){12}[A-Z2-7]{4}$/);
  await unlock(meta(made), { passphrase: PASS });
  await unlock(meta(made), { recoveryKey: made.recoveryKey.toLowerCase().replace(/-/g, " ") });
  await assert.rejects(unlock(meta(made), { passphrase: PASS + "x" }), VaultLockedError);
  await assert.rejects(unlock(meta(made), { recoveryKey: formatRecovery(random(32)) }), VaultLockedError);
  await assert.rejects(createVault("short"), /at least 12/);
  await assert.rejects(createVault("password1234"), /too easy/);
});

test("items round-trip, and a box moved to another item or version fails to open", async () => {
  const made = await createVault(PASS);
  const id = newItemId();
  const fields = { ...emptyFields(), label: "Shop", username: "me@shop.test", secret: "CANARY-7f3a-secret", accountId: "acc_abcdefgh" };
  const record = await encryptItem(made.vk, { id, kind: "login", version: 1 }, fields);
  assert.equal(JSON.stringify(record).includes("CANARY"), false, "no plaintext in what goes to the gateway");
  const opened = await decryptItem(made.vk, { ...record, createdBy: "owner", updatedAt: 1 });
  assert.equal(opened.secret, "CANARY-7f3a-secret");
  assert.equal(opened.moved, false);
  await assert.rejects(decryptItem(made.vk, { ...record, id: newItemId() }));
  await assert.rejects(decryptItem(made.vk, { ...record, version: 2 }));
  await assert.rejects(decryptItem(made.vk, { ...record, kind: "note" }));
  const relinked = await decryptItem(made.vk, { ...record, accountId: "acc_someoneelse" });
  assert.equal(relinked.moved, true, "an account link changed outside Glass is flagged");
  const other = await createVault(PASS);
  await assert.rejects(decryptItem(other.vk, record), "another vault's key cannot open it");
});

test("a new passphrase or recovery key keeps every item readable", async () => {
  const made = await createVault(PASS);
  const record = await encryptItem(made.vk, { id: newItemId(), kind: "note", version: 1 }, { ...emptyFields(), notes: "hello" });
  const changed = await rewrapPassphrase(meta(made), { recoveryKey: made.recoveryKey }, "copper violin harbor seven owls");
  const next = { ...meta(made), ...changed };
  await assert.rejects(unlock(next, { passphrase: PASS }), VaultLockedError);
  const vk = await unlock(next, { passphrase: "copper violin harbor seven owls" });
  assert.equal((await decryptItem(vk, record)).notes, "hello");
  const rotated = await newRecoveryKey(next, { passphrase: "copper violin harbor seven owls" });
  const after = { ...next, recoveryWrappedVk: rotated.body.recoveryWrappedVk };
  await assert.rejects(unlock(after, { recoveryKey: made.recoveryKey }), VaultLockedError);
  await unlock(after, { recoveryKey: rotated.recoveryKey });
});

test("recovery keys survive formatting", () => {
  const bytes = random(32);
  assert.deepEqual([...parseRecovery(formatRecovery(bytes))], [...bytes]);
  assert.throws(() => parseRecovery("not a key"), VaultLockedError);
});

test("the generator is long, mixed and random; strength and health are honest", () => {
  const a = generatePassword(24);
  assert.equal(a.length, 24);
  assert.match(a, /[a-z]/);
  assert.match(a, /[A-Z]/);
  assert.match(a, /[0-9]/);
  assert.match(a, /[^A-Za-z0-9]/);
  assert.notEqual(a, generatePassword(24));
  assert.equal(generatePassword(4).length, 12, "never shorter than 12");
  assert.equal(strength("password123"), "weak");
  assert.equal(strength(a), "strong");
  const now = Date.now();
  const items = [
    { id: "a", kind: "login", secret: "hunter2hunter2", updatedAt: now },
    { id: "b", kind: "login", secret: "hunter2hunter2", updatedAt: now - 400 * 86400000 },
    { id: "c", kind: "login", secret: a, updatedAt: now },
  ];
  const h = health(items, now);
  assert.deepEqual(h.reused.sort(), ["a", "b"]);
  assert.deepEqual(h.old, ["b"]);
  assert.ok(h.weak.includes("a") && !h.weak.includes("c"));
});

test("TOTP matches RFC 6238", async () => {
  const seed = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
  assert.equal(await totpCode(seed, 59_000, 8), "94287082");
  assert.equal(await totpCode(seed, 1111111109_000, 8), "07081804");
  await assert.rejects(totpCode("not a seed"));
});

test("imports read Bitwarden JSON and CSV exports and skip cards", () => {
  const bw = JSON.stringify({ encrypted: false, items: [
    { type: 1, name: "Shop", login: { username: "me", password: "pw-1", uris: [{ uri: "https://shop.test" }], totp: "JBSWY3DPEHPK3PXP" } },
    { type: 2, name: "Wifi", notes: "router notes" },
    { type: 3, name: "Visa", card: { number: "4111" } },
  ] });
  const fromBw = parseExport("bitwarden.json", bw);
  assert.equal(fromBw.items.length, 2);
  assert.equal(fromBw.skipped, 1, "payment cards are never imported");
  assert.equal(fromBw.items[0].fields.totp, "JBSWY3DPEHPK3PXP");
  assert.throws(() => parseExport("x.json", JSON.stringify({ encrypted: true, items: [] })), /encrypted/);

  const csv = 'name,url,username,password,note\n"Shop, Inc",https://shop.test,me,"p""w,1","line1\nline2"\n,,,,\nBank,https://bank.test,,,\n';
  const fromCsv = parseExport("chrome.csv", csv);
  assert.equal(fromCsv.items.length, 1);
  assert.equal(fromCsv.items[0].fields.label, "Shop, Inc");
  assert.equal(fromCsv.items[0].fields.secret, 'p"w,1');
  assert.equal(fromCsv.items[0].fields.notes, "line1\nline2");
  assert.equal(fromCsv.skipped, 1);
  assert.equal(csvRows("a,b\r\nc,d").length, 2);
  assert.throws(() => parseExport("x.csv", "name,url\nx,y"), /no password column/);
});
