# V4 slice 5 golden page cards

Twelve **synthetic** JSON page-card fixtures for `phone_locate(goal)` ranking tests.

- Not Pixel 8 dumps. No physical capture was taken for this run.
- Physical locate remains **UNVERIFIED**.
- Each file wraps an observation with `goal` + `expectedTarget`.
- `pageText` is `cyclone-page-text-v1`. `pageSummary` is `cyclone-page-summary-v1`.
- No plaintext passwords. No 2500-node raw trees.

Run from `tools/codex-phone-mcp`:

```text
python -m unittest tests.test_golden_locate
```
