// Verify X-Cyclone-Signature (SPEC.md §4) in Node 18+, no dependencies. Checked against schemas/signature-vectors.json
// by tests/test_ports.py::test_node_verifier_matches_the_vectors.
import crypto from "node:crypto";

// keys: { k1: "secret", k2: "next-secret" }; rawBody: Buffer; path includes the query string
export function verifyCyclone(keys, header, method, path, rawBody, seenIds, nowS = Date.now() / 1000) {
  const p = Object.fromEntries((header || "").split(",").map(s => s.trim().split(/=(.*)/s).slice(0, 2)));
  const key = keys[p.kid || "k1"];
  if (!key || !p.id || !p.v1 || !/^\d+$/.test(p.t || "")) return false;
  if (Math.abs(nowS - Number(p.t)) > 300) return false;
  const bodyHash = crypto.createHash("sha256").update(rawBody).digest("hex");
  const s = `${p.t}\n${p.id}\n${method.toUpperCase()}\n${path}\n${bodyHash}`;
  const want = crypto.createHmac("sha256", key).update(s).digest();
  const got = Buffer.from(p.v1, "hex");
  if (got.length !== want.length || !crypto.timingSafeEqual(got, want)) return false;
  if (seenIds.has(p.id)) return false;            // replay
  seenIds.set(p.id, nowS + 600);                  // prune entries older than their expiry now and then
  return true;
}
