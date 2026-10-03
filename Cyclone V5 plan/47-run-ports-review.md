# Plan 47: Run Ports design review, "would it hold at Google scale?"

Date: 2026-10-01. Subject: the `cyclone.ports/1` contract (plan 45, `tools/cyclone-ports-sdk/SPEC.md`).
Question from the owner: is this the final design? Will it stay reliable as we change and improve Ports? How would a
company with billions of clients design it?

## 1. Verdict

The first draft was a good shape, but **it was not final.** It would have broken plugins the first time the contract
grew. In the most important case, a plugin built with the SDK rejected any `run.event` stage it didn't know. Adding a
stage such as "paused" would have broken every plugin.

Nothing had been built against the draft yet, so all the gaps were fixed the same day, in the contract, the SDK, the
Dev Hub and the checker. The checker now **tests** evolvability, and CI runs it. With that, the plugin-facing contract
is ready to freeze, and connectors can be built on it now.

## 2. What was checked, and what changed

The left column is the practice big platforms use: Google's API Improvement Proposals (AIPs), Pub/Sub push and pull,
Cloud Storage signed URLs, and the webhook designs at Stripe and GitHub.

| # | Practice at scale | Draft | Now |
|---|---|---|---|
| 1 | **Tolerant reader, open enums** (AIP-126, AIP-180): old clients ignore new fields and values | ✗ the SDK rejected unknown stages | ✓ plugins read structure only; the hub checks strictly before sending; the checker sends future-looking envelopes and **fails** plugins that break (tested) |
| 2 | **Major version in the contract, additive minor changes** (AIP-180/185) | partial | ✓ written rules for what may and may not change in `/1` (SPEC §9); `/2` runs side by side for 12 months |
| 3 | **Idempotency keys** (AIP-155 `request_id`, Stripe `Idempotency-Key`) | ✗ a retried delivery got 409 and looked like a failure | ✓ `deliveryId`: a repeat gets 200 `duplicate: true`; envelopes have `id` to de-duplicate on |
| 4 | **At-least-once delivery, backoff, `Retry-After`** (Pub/Sub push) | ✗ one attempt | ✓ the hub retries out ports with the same `id`; the SDK's `deliver` retries 429, 5xx and network errors |
| 5 | **Signed webhooks covering what matters, with replay protection** (Stripe, GitHub) | partial: the signature covered time and body only | ✓ it now covers time, request id, method, path and body hash; request ids are single-use (replay → 401); a request signed for `/await` can't be replayed to `/cancel` |
| 6 | **Key rotation without downtime** (key ids, JWKS) | ✗ one secret, no key id | ✓ `kid` in the header; plugins accept `CYCLONE_PLUGIN_SECRET` and `…_NEXT` during a switch; the `v2=` slot is reserved for a future asymmetric scheme |
| 7 | **One error model** (AIP-193, `google.rpc.Status`) | ✗ ad-hoc strings | ✓ `{"error": {"code", "message", "retryable"}}` everywhere |
| 8 | **Capability negotiation** | ✗ | ✓ manifest `features`; unknown names ignored; `pull`, `upload` and `mcp` reserved |
| 9 | **Extensibility without a central bottleneck** | ✗ closed catalog | ✓ extension ports `x.<plugin>.<name>`, namespaced to the plugin, always personal, secret-shaped fields refused |
| 10 | **Distributed tracing** (W3C Trace Context) | ✗ | ✓ the hub sends `traceparent`; `deliver` forwards it |
| 11 | **Redelivered work is the same work** | ✗ | ✓ the same `awaitId` arriving twice is the same wait (examples fixed; checker tests it) |
| 12 | **A conformance suite as the compatibility gate, in CI** | ✓ (basic) | ✓ extended (path binding, replay, forward-compat, repeated await); runs in `pc-companion-ci.yml`; cross-language **signature test vectors**, checked against an independent implementation |
| 13 | **Large payloads by reference** (GCS signed URLs) | ✓ out (one-time artifact links) | ✓ out; `upload` is reserved for in (base64 up to 20 MB stays for v1) |
| 14 | **Pull for clients that can't take pushes** (Pub/Sub pull) | ✗ | reserved feature `pull` (P5); not needed for local plugins |
| 15 | **Least privilege, consent, audit** | ✓ | ✓ unchanged: personal ports are ticked per plugin, secrets go only through the hub and the vault, the audit log is metadata only |

### Considered and deliberately not adopted

| Practice | Why not |
|---|---|
| **Protobuf/gRPC as the wire format** | Plugin authors are owners and agents writing small tools. JSON over HTTP works with curl and with every language, and the gateway already speaks it. JSON Schemas are published; an OpenAPI file can be generated later without changing the wire. |
| **Asymmetric signatures (OIDC/JWT, Ed25519) now** | Each plugin has its own key, and plugins are local or owner-run, so HMAC has the same security here with zero dependencies. The header has a `v2=` slot, so a public-key scheme can be added beside `v1` without breaking anyone. |
| **mTLS between hub and plugin** | Loopback for local plugins; https plus signatures plus single-use port tokens for remote ones. Revisit with remote plugins in P5. |

## 3. What scale actually means here

Cyclone runs one owner's phones, not billions of clients. A plugin sees the same contract whether the hub handles one
run or a million, so what matters for plugins is the **client-facing semantics**:
- at-least-once delivery;
- idempotency;
- retries;
- versioning;
- one error model.

Those are now the ones big platforms use. The remaining scale work is inside the hub and invisible to plugins:
durable queues, persisting awaits across restarts, per-plugin rate limits and a dead-letter view in Glass. It's listed
below as requirements for whoever builds the Port Hub.

## 4. Requirements for the gateway's Port Hub (P1–P5)

1. Import `tools/cyclone-ports-sdk/cyclone_ports/catalog.py`. Don't copy it.
2. Pass the kit's tests and the checker against the three example plugins before every release; CI already runs them.
3. **Persist awaits** (runId, port, awaitId, token hash, expiry), so a gateway restart re-sends `/await` with the same
   `awaitId` and the run keeps waiting.
4. **Retries:**
   - out ports: up to 3 attempts with backoff, honouring `Retry-After`, with the same envelope `id`;
   - after that, mark the message failed in the run timeline and in a "failed deliveries" list in Glass.
5. **Per-plugin limits:**
   - concurrency;
   - requests per minute (answer your own `429`);
   - pause a plugin after repeated failures;
   - show its health in Glass.
6. **Manifest pinning:** a change to `serves`, `endpoint`, `needs` or `features` pauses the plugin until the owner
   approves it again.
7. **Key rotation** in Glass: create `k<N+1>`, show it once, switch after the plugin's `/health` answers, then retire
   the old key.
8. **Secrets:**
   - `code.in` goes to sealed delivery (plan 33) at once;
   - nothing persists the code;
   - the audit log keeps metadata only (the Dev Hub's test proves the pattern).

## 5. Confidence

| Question | Confidence | Why it isn't higher |
|---|---|---|
| A connector that passes the checker today works with the real Port Hub | **9/10** | The real hub isn't built yet. The gate (shared catalog, same conformance suite in CI, requirement §4.2) is what makes this hold. |
| Ports can grow (stages, fields, ports, features, extensions) without breaking connectors | **9.5/10** | Proven by tests for stages, fields, match keys, features and extension ports. Untested: a real `/2` migration. |
| Security of the plugin boundary | **9/10** | Strong for local plugins. Remote plugins (P5) still need the pinned-key and mTLS review. |
| Hub-side reliability at load | **not rated** | Unbuilt. Requirements are in §4. |
| Physical end to end (a real SMS forwarder phone and a real phone run) | **UNVERIFIED** | Needs the gateway Port Hub (P3) and a device. |

**Overall: 9/10 that a connector built now will plug into Cyclone Ports and keep working as Ports improves.** The last
point is the real hub, and it is closed by building the hub against this same suite.

Connector builders start at: `Cyclone V5 plan/HANDOFF-build-a-connector.md`.
